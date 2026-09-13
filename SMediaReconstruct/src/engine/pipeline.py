"""End-to-end orchestration with structured events for the UI.

Every major step verifies its output. Two random preflight tests must pass
before full processing. A final boundary/beep scan must reach zero remaining
artifacts before the output is accepted. Source files are never deleted;
cleanup happens only after a verified final output.
"""
from __future__ import annotations

import random
import shutil
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from . import audio as beep
from .batching import BATCH_SIZE, ClipEngine, batch_count
from .detection import (EXO_EXT, MediaFile, collect, compatible, pair_separate,
                        probe_media)
from .exo import ExoEngine, scan_exo
from .ffmpeg import FF
from .hardware import resolve_encoder
from .util import human_seconds, human_size, inv_float, numeric_sort_key
from .verification import describe, stream_summary, verify_has_av

TEST_MIN_SEC = 20.0
TEST_MAX_SEC = 60.0
DISK_MARGIN = 1.15   # require 15% headroom over the estimate


@dataclass
class Config:
    quality: str          # copy_first | normalize_all
    hardware: str         # gpu_max | cpu_only | cpu_gpu_max
    size: str             # large | compressed
    output_format: str    # mkv | mp4
    output: Path
    inputs: list[Path]
    recursive: bool
    gpu_ok: bool = False   # set at run time after a real NVENC probe


class Cancelled(Exception):
    pass


class Pipeline:
    """One pipeline instance per job; emits events via `emit`."""

    def __init__(self, emit: Callable[[dict], None], cancel_event: threading.Event):
        self.emit = emit
        self.cancel = cancel_event
        self.ff: FF | None = None
        self._t0 = time.time()
        self._base = 0.0
        self._span = 100.0
        self.last_preview: Path | None = None
        self._stage_name = ""

    # -- helpers -----------------------------------------------------------
    def log(self, line: str):
        self.emit({"type": "log", "line": str(line)})

    def status(self, value: str):
        self.emit({"type": "status", "value": value})

    def step(self, sid: str, label: str, state: str = "active"):
        if state == "active":
            self._stage_name = label
        self.emit({"type": "step", "id": sid, "label": label, "state": state})

    def _band(self, base: float, span: float):
        self._base, self._span = base, span

    def _progress(self, pct: float, speed: float | None = None, cur=None, total=None, stage=""):
        overall = self._base + self._span * (max(0.0, min(100.0, pct)) / 100.0)
        elapsed = time.time() - self._t0
        frac = max(0.001, overall / 100.0)
        eta = elapsed / frac - elapsed if overall > 0 else None
        self.emit({"type": "progress", "stage": stage,
                   "stageName": getattr(self, "_stage_name", ""),
                   "pct": round(overall, 1),
                   "stagePct": round(pct, 1),
                   "speed": (f"{speed:.1f}x" if speed else None),
                   "elapsed": int(elapsed), "eta": (int(eta) if eta and eta > 0 else None),
                   "cur": cur, "total": total})

    def _ff(self, stage: str):
        def prog(pct, speed):
            self._progress(pct, speed, stage=stage)
        return FF(self.log, prog, self.cancel)

    def _check(self):
        if self.cancel.is_set():
            raise Cancelled("Operation cancelled by user.")

    def _emit_preview_file(self, jp: Path | None):
        if not jp:
            return
        try:
            import base64
            b = base64.b64encode(jp.read_bytes()).decode("ascii")
            self.last_preview = jp
            self.emit({"type": "preview", "dataUrl": f"data:image/jpeg;base64,{b}"})
        except Exception:
            pass

    def _preview_from(self, engine: ClipEngine, media: Path, work: Path):
        out = work / "preview_cell.jpg"
        self._emit_preview_file(engine.last_frame_jpeg(media, out))

    def _preview_at(self, media: Path, seconds: float, work: Path):
        """Grab a frame at a specific timestamp of a completed source file.

        Used for the live EXO preview: we pull the frame from the already
        reconstructed continuous video at the position currently being written,
        so the preview follows progress without touching the output file. Runs
        on a quiet FF so it neither spams the log nor moves the progress bar.
        """
        qff = FF(lambda *_: None, None, self.cancel)
        out = work / "preview_cell.jpg"
        try:
            qff.run(["-y", "-ss", inv_float(max(0.0, seconds)), "-i", str(media),
                     "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", str(out)],
                    label="Preview frame")
            if out.exists():
                self._emit_preview_file(out)
        except Exception:
            pass

    # -- disk-space preflight ---------------------------------------------
    def _preflight_space(self, target_dir: Path, needed: int, label: str):
        """Refuse to start writing when free space can't hold the estimate.

        This is the guard that would have prevented the 151 GB partial write:
        it compares the estimated output size (plus headroom) against the free
        space on the destination volume before any bytes are written.
        """
        try:
            target_dir.mkdir(parents=True, exist_ok=True)
            free = shutil.disk_usage(str(target_dir)).free
        except Exception:
            return  # if we can't measure, don't block
        required = int(needed * DISK_MARGIN)
        self.log(f"Disk check [{label}]: need ~{human_size(required)}, "
                 f"free {human_size(free)} on {target_dir}")
        if free < required:
            e = RuntimeError(
                f"Insufficient disk space for {label}.\n"
                f"  Estimated output : ~{human_size(needed)}\n"
                f"  With safety margin: ~{human_size(required)}\n"
                f"  Free on {target_dir}: {human_size(free)}\n"
                f"  Short by         : ~{human_size(required - free)}\n\n"
                "Free up space or choose another drive, then retry. "
                "Nothing was written.")
            e.stage = "DISK SPACE"  # type: ignore[attr-defined]
            raise e

    # -- ANALYZE -----------------------------------------------------------
    def analyze(self, cfg: Config):
        self._t0 = time.time()
        self.status("SCANNING")
        self.step("scan", "INPUT SCAN")
        ff = self._ff("scan")
        self.ff = ff
        ff.ensure()
        paths = collect(cfg.inputs, cfg.recursive)
        self.log(f"Collected {len(paths)} files from input.")
        exos = [p for p in paths if p.suffix.lower() == EXO_EXT]
        info: dict = {"mode": None}
        if exos:
            root = cfg.inputs[0] if cfg.inputs and cfg.inputs[0].is_dir() else exos[0].parent
            scan = scan_exo(root, self.log)
            info = {
                "mode": "EXO",
                "exo_files": len(scan.files),
                "video_fragments": len(scan.video_fragments),
                "audio_fragments": len(scan.audio_fragments),
                "video_track": scan.video_track_id,
                "audio_track": scan.audio_track_id,
                "manifest": bool(scan.manifest),
            }
            if len(scan.video_fragments) != len(scan.audio_fragments):
                info["warning"] = (f"Fragment count mismatch: {len(scan.video_fragments)} video "
                                   f"vs {len(scan.audio_fragments)} audio. Source may be incomplete.")
        else:
            media = probe_media(ff, paths, self.log)
            if media["mixed"]:
                info = {"mode": "MIXED", "clips": len(media["mixed"]),
                        "batches": batch_count(len(media["mixed"]))}
            elif media["video_only"] and media["audio_only"]:
                info = {"mode": "SEPARATE", "video": len(media["video_only"]),
                        "audio": len(media["audio_only"]),
                        "batches": batch_count(len(media["video_only"]))}
                if len(media["video_only"]) != len(media["audio_only"]):
                    info["warning"] = (f"Separate stream mismatch: {len(media['video_only'])} video "
                                       f"vs {len(media['audio_only'])} audio.")
            else:
                raise RuntimeError("No supported media layout was detected (EXO, mixed A/V, or separate A/V).")
        self.emit({"type": "detect", "info": info})
        self.step("scan", "INPUT SCAN", "done")
        self.status("READY")
        self.log("Analysis complete. Preflight tests and full processing are ready.")

    # -- RUN ---------------------------------------------------------------
    def run(self, cfg: Config):
        self._t0 = time.time()
        work = Path(tempfile.mkdtemp(prefix="smr_job_"))
        log_path = work / "job.log"

        def jlog(s):
            self.emit({"type": "log", "line": str(s)})
            try:
                with log_path.open("a", encoding="utf-8") as f:
                    f.write(str(s) + "\n")
            except Exception:
                pass

        self.log = jlog  # type: ignore[assignment]
        try:
            jlog(f"=== {cfg.output.name} | quality={cfg.quality} hw={cfg.hardware} "
                 f"size={cfg.size} fmt={cfg.output_format} ===")
            cfg.output.parent.mkdir(parents=True, exist_ok=True)
            ff = self._ff("scan")
            self.ff = ff
            ff.ensure()

            # Resolve the real encoder for the chosen hardware option.
            cfg.gpu_ok, enc_label = resolve_encoder(cfg.hardware)
            if cfg.quality == "copy_first":
                active = "stream copy — no encoding (hardware not used)"
            else:
                active = enc_label
            self.emit({"type": "hardware", "requested": cfg.hardware,
                       "gpu": cfg.gpu_ok, "encoder": active})
            jlog(f"Hardware: requested={cfg.hardware} · active encoder={active}")
            if cfg.hardware in ("gpu_max", "cpu_gpu_max") and not cfg.gpu_ok \
                    and cfg.quality != "copy_first":
                jlog("NOTE: GPU (NVENC) was requested but is unavailable on this "
                     "machine; falling back to CPU (libx264).")

            paths = collect(cfg.inputs, cfg.recursive)
            exos = [p for p in paths if p.suffix.lower() == EXO_EXT]
            if exos:
                self._run_exo(ff, cfg, paths, exos, work)
            else:
                self._run_clips(ff, cfg, paths, work)

            # Final verification
            self.status("VERIFYING")
            self.step("final", "FINAL VERIFICATION")
            s = verify_has_av(ff, cfg.output, require_audio=True)
            jlog(f"FINAL: {human_seconds(s['duration'])} | {describe(s)}")
            self.step("final", "FINAL VERIFICATION", "done")

            self.step("submit", "SUBMIT / CLEANUP")
            shutil.rmtree(work, ignore_errors=True)
            self.step("submit", "SUBMIT / CLEANUP", "done")
            self.status("DONE")
            self._progress(100.0)
            self.emit({"type": "done", "output": str(cfg.output.resolve()),
                       "meta": {"duration": human_seconds(s["duration"]),
                                "detail": describe(s), "size": s["size"]}})
        except Cancelled:
            self.status("CANCELLED")
            self.emit({"type": "cancelled", "work": str(work)})
        except Exception as e:
            self.status("FAILED")
            self.emit({
                "type": "error",
                "stage": getattr(e, "stage", None) or "PROCESSING",
                "problem": str(e),
                "work": str(work),
                "log": str(log_path),
                "logText": (log_path.read_text(encoding="utf-8", errors="replace")
                            if log_path.exists() else str(e)),
            })

    # -- EXO path ----------------------------------------------------------
    def _run_exo(self, ff, cfg, paths, exos, work):
        self.status("BUILDING")
        self.step("exo", "EXO RECONSTRUCTION")
        self._band(0, 45)
        root = cfg.inputs[0] if cfg.inputs and cfg.inputs[0].is_dir() else exos[0].parent
        scan = scan_exo(root, self.log)
        self.emit({"type": "detect", "info": {
            "mode": "EXO", "exo_files": len(scan.files),
            "video_fragments": len(scan.video_fragments),
            "audio_fragments": len(scan.audio_fragments),
            "video_track": scan.video_track_id, "audio_track": scan.audio_track_id}})

        # Preflight: the continuous streams are ~ the sum of the fragment bytes.
        frag_bytes = self._sum_sizes([p for _, p in scan.video_fragments]
                                     + [p for _, p in scan.audio_fragments]
                                     + [scan.video_init, scan.audio_init])
        self._preflight_space(work, frag_bytes, "reconstruction workspace")

        engine = ExoEngine(ff, self.log)
        nfrag = len(scan.video_fragments) + len(scan.audio_fragments)

        def recon_prog(done, total):
            self._progress(done / total * 100.0 if total else 0.0,
                           cur=done, total=total, stage="exo")

        video, audio = engine.reconstruct(scan, work, on_progress=recon_prog)
        self._check()
        vs = stream_summary(ff, video)
        as_ = stream_summary(ff, audio)
        vd, ad = vs["duration"], as_["duration"]
        self.log(f"Continuous video {human_seconds(vd)} | continuous audio {human_seconds(ad)}")
        self.step("exo", "EXO RECONSTRUCTION", "done")
        self._progress(100.0)
        # Auto-preview: show a frame from the reconstructed video immediately.
        self._preview_at(video, max(1.0, min(vd, ad) * 0.02), work)

        # Two random preflight tests on the continuous streams
        self.status("TESTING")
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS")
        self._band(45, 10)
        self._exo_tests(ff, video, audio, min(vd, ad), work)
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS", "done")

        # Output-drive preflight before writing the final movie.
        out_est = self._sum_sizes(list({video, audio}))  # copy ≈ source; normalize ≈ same order
        self._preflight_space(cfg.output.parent, out_est, "final output")

        # Final output (copy-first = remux; normalize-all = GPU/CPU re-encode)
        self.status("BUILDING")
        self.step("mux", "FINAL OUTPUT")
        self._band(55, 30)
        dur = min(vd, ad)
        last_prev = [time.time()]

        def mux_prog(pct, speed):
            self._progress(pct, speed, stage="mux")
            now = time.time()
            if cfg.quality == "normalize_all" and dur > 0 and now - last_prev[0] > 6.0:
                last_prev[0] = now
                self._preview_at(video, dur * max(0.0, min(1.0, pct / 100.0)), work)

        ffm = FF(self.log, mux_prog, self.cancel)
        ExoEngine(ffm, self.log).mux(video, audio, cfg.output, cfg, duration=dur)
        self._progress(100.0)
        self._preview_at(video, dur * 0.98, work)

        # Hard audio-integrity check: the merged output MUST carry an audio
        # stream of comparable length. This guarantees the app can never
        # silently produce a video-only (muted) file.
        osum = stream_summary(ff, cfg.output)
        if not osum.get("audio"):
            raise RuntimeError(
                "Merged output has video but NO audio stream. Audio reconstruction "
                "or muxing failed — output rejected. Check the diagnostic log for the "
                "audio init / audio-fragment counts.")
        adur = float((osum.get("audio") or {}).get("duration") or 0) or osum.get("duration", 0)
        if osum.get("duration", 0) > 0 and adur < 0.5 * osum["duration"]:
            raise RuntimeError(
                f"Merged audio is far shorter than video ({adur:.0f}s vs "
                f"{osum['duration']:.0f}s) — the merge is likely broken. Output rejected.")
        self.log(f"Audio-integrity OK · {describe(osum)}")
        self.step("mux", "FINAL OUTPUT", "done")

        # Beep verification: computed fragment boundaries + random regions
        self.status("VERIFYING")
        self.step("beep", "BOUNDARY / BEEP SCAN")
        self._band(85, 10)
        boundaries = self._exo_boundaries(scan, ad)
        result = beep.scan_boundaries(ff, cfg.output, boundaries, self.log,
                                      on_progress=lambda p: self._progress(p, stage="beep"))
        self.emit({"type": "beep", "scanned": result["scanned"], "clean": result["clean"],
                   "suspicious": result["suspicious"], "repaired": 0,
                   "remaining": result["suspicious"]})
        if result["suspicious"] > 0:
            raise RuntimeError(
                f"Boundary scan found {result['suspicious']} suspicious seam(s) in the "
                "continuous stream. This should not happen on a clean EXO source; the "
                "cache is likely incomplete. Output was not accepted.")
        self.log("Boundary scan clean. Continuous-AAC path avoids per-fragment decoder restarts.")
        self.step("beep", "BOUNDARY / BEEP SCAN", "done")

    @staticmethod
    def _sum_sizes(paths) -> int:
        total = 0
        for p in paths:
            try:
                if p and Path(p).exists():
                    total += Path(p).stat().st_size
            except Exception:
                pass
        return total



    def _exo_boundaries(self, scan, audio_dur: float) -> list[float]:
        """Derive audio-fragment boundary times from tfdt timeline; sample them."""
        ts = [t for t, _ in scan.audio_fragments]
        pts: list[float] = []
        if len(ts) >= 2 and ts[-1] > ts[0]:
            span = ts[-1] - ts[0]
            for t in ts:
                frac = (t - ts[0]) / span if span else 0
                pts.append(round(frac * audio_dur, 3))
        # Sample up to ~60 boundaries spread across the timeline, plus random regions.
        pts = sorted(set(p for p in pts if 0.2 < p < audio_dur - 0.2))
        if len(pts) > 60:
            stepn = len(pts) / 60.0
            pts = [pts[int(i * stepn)] for i in range(60)]
        for _ in range(8):
            pts.append(round(random.uniform(0.2, max(0.3, audio_dur - 0.2)), 3))
        return sorted(set(pts))

    def _exo_tests(self, ff, video, audio, dur, work):
        if dur <= 0:
            raise RuntimeError("Reconstructed streams report zero duration.")
        combined = (video == audio)   # MIXED: one file already holds A+V
        segs = self._random_regions(dur)
        for idx, (start, length) in enumerate(segs, 1):
            self._check()
            d = Path(tempfile.mkdtemp(prefix=f"smr_t{idx}_"))
            try:
                o = d / "av.mkv"
                if combined:
                    ff.run(["-y", "-ss", inv_float(start), "-i", str(video), "-t", inv_float(length),
                            "-map", "0:v:0", "-map", "0:a:0", "-c", "copy", str(o)],
                           duration=length, label=f"Test {idx}")
                else:
                    v, a = d / "v.mkv", d / "a.m4a"
                    ff.run(["-y", "-ss", inv_float(start), "-i", str(video), "-t", inv_float(length),
                            "-map", "0:v:0", "-c", "copy", str(v)], duration=length, label=f"Test {idx} video")
                    ff.run(["-y", "-ss", inv_float(start), "-i", str(audio), "-t", inv_float(length),
                            "-map", "0:a:0", "-c", "copy", str(a)], duration=length, label=f"Test {idx} audio")
                    ff.run(["-y", "-i", str(v), "-i", str(a), "-map", "0:v:0", "-map", "1:a:0",
                            "-c", "copy", "-shortest", str(o)], label=f"Test {idx} mux")
                verify_has_av(ff, o, require_audio=True)
                self.log(f"TEST {idx}: PASS  region {human_seconds(start)}+{int(length)}s")
                self._progress(idx / len(segs) * 100.0, stage="tests")
            finally:
                shutil.rmtree(d, ignore_errors=True)

    def _random_regions(self, dur: float) -> list[tuple[float, float]]:
        length = max(10.0, min(TEST_MIN_SEC, dur / 3.0))
        if dur <= length * 2 + 1:
            return [(0.0, min(length, dur))]
        a = random.uniform(0.0, dur * 0.45)
        b = random.uniform(dur * 0.5, max(dur * 0.5, dur - length - 0.5))
        return [(round(a, 3), length), (round(b, 3), length)]

    # -- Clip path (mixed or separate) ------------------------------------
    def _run_clips(self, ff, cfg, paths, work):
        engine = ClipEngine(ff, self.log)
        media = probe_media(ff, paths, self.log)
        if media["mixed"]:
            items = sorted(media["mixed"], key=lambda x: numeric_sort_key(x.path))
        elif media["video_only"] and media["audio_only"]:
            self.status("BUILDING")
            self.step("pair", "PAIR SEPARATE A/V")
            self._band(0, 15)
            pairs = pair_separate(media["video_only"], media["audio_only"], self.log)
            pdir = work / "paired"
            pdir.mkdir()
            items = []
            for i, (v, a) in enumerate(pairs, 1):
                self._check()
                f = pdir / f"{i:06d}.mkv"
                ff.run(["-y", "-i", str(v.path), "-i", str(a.path), "-map", "0:v:0", "-map", "1:a:0",
                        "-c:v", "copy", "-c:a", "copy", "-shortest", str(f)], label=f"Pair {i}/{len(pairs)}")
                s = stream_summary(ff, f)
                items.append(MediaFile(f, s["duration"], s["video"], s["audio"]))
                self._progress(i / len(pairs) * 100.0, cur=i, total=len(pairs), stage="pair")
            self.step("pair", "PAIR SEPARATE A/V", "done")
        else:
            raise RuntimeError("No supported clip layout detected.")

        total = len(items)
        self.log(f"Clips: {total} | durations {min(m.duration for m in items):.3f}-{max(m.duration for m in items):.3f}s (dynamic)")

        # Disk preflight: re-encode ≈ sum(inputs); copy ≈ sum(inputs).
        in_bytes = self._sum_sizes([m.path for m in items])
        self._preflight_space(cfg.output.parent,
                              int(in_bytes * (1.2 if cfg.quality == "normalize_all" else 1.05)),
                              "final output")

        # Two random preflight tests from random clip windows
        self.status("TESTING")
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS")
        self._band(15, 12)
        self._clip_tests(engine, items, work, cfg)
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS", "done")

        copy_ok = cfg.quality == "copy_first" and compatible(items)
        normalize = not copy_ok

        # Build batches
        self.status("NORMALIZING" if normalize else "BATCHING")
        self.step("batch", f"BUILD {batch_count(total)} BATCHES × {BATCH_SIZE}")
        self._band(27, 45)
        batches = engine.build_batches(
            items, work, cfg, normalize=normalize, protect_indices=None,
            emit_batch=lambda b, c: (self._progress(b / c * 100.0, cur=b, total=c, stage="batch"),
                                     self.emit({"type": "batch", "cur": b, "total": c})),
            preview=lambda seg: self._preview_from(engine, seg, work),
        )
        self.step("batch", f"BUILD {batch_count(total)} BATCHES × {BATCH_SIZE}", "done")

        # Master concat (lossless copy of batches)
        self.step("master", "MASTER CONCAT")
        self._band(72, 6)
        master = work / "master.mkv"
        engine.concat_masters(batches, master)
        self._preview_from(engine, master, work)
        self.step("master", "MASTER CONCAT", "done")

        # Beep scan at clip boundaries → local repair → rescan
        self.status("VERIFYING")
        self.step("beep", "BOUNDARY / BEEP SCAN")
        self._band(78, 14)
        boundaries = self._clip_boundaries(items)
        result = beep.scan_boundaries(ff, master, boundaries, self.log,
                                      on_progress=lambda p: self._progress(p * 0.6, stage="beep"))
        repaired = 0
        if result["suspicious"] > 0:
            self.log(f"Repairing {result['suspicious']} flagged boundary(ies) with local endpoint protection…")
            protect = self._flagged_clip_indices(items, result["flagged"])
            rbatches = engine.build_batches(items, work, cfg, normalize=True,
                                            protect_indices=protect, preview=lambda seg: None)
            master2 = work / "master_repaired.mkv"
            engine.concat_masters(rbatches, master2)
            result2 = beep.scan_boundaries(ff, master2, boundaries, self.log,
                                           on_progress=lambda p: self._progress(60 + p * 0.4, stage="beep"))
            repaired = result["suspicious"] - result2["suspicious"]
            master = master2
            result = result2
        self.emit({"type": "beep", "scanned": result["scanned"], "clean": result["clean"],
                   "suspicious": result["suspicious"], "repaired": repaired,
                   "remaining": result["suspicious"]})
        if result["suspicious"] > 0:
            raise RuntimeError(
                f"{result['suspicious']} boundary artifact(s) remain after local repair. "
                "Output was not accepted. Try NORMALIZE ALL, or review the source clips.")
        self.step("beep", "BOUNDARY / BEEP SCAN", "done")

        # Finalize to chosen container/quality
        self.step("out", "WRITE FINAL OUTPUT")
        self._band(92, 8)
        self._finalize(ff, master, items, cfg, work)
        self.step("out", "WRITE FINAL OUTPUT", "done")

    def _finalize(self, ff, master, items, cfg, work):
        # master is already encoded/normalized (or copy-compatible). Remux losslessly.
        args = ["-y", "-i", str(master), "-map", "0:v:0", "-map", "0:a:0", "-c", "copy"]
        if cfg.output_format == "mp4":
            args += ["-movflags", "+faststart"]
        ff.run(args + [str(cfg.output)], label="Final container write")

    def _clip_tests(self, engine, items, work, cfg):
        n = len(items)
        span = min(8, n)
        starts = [0]
        if n > span * 2:
            starts = [random.randint(0, n - span - 1), random.randint(0, n - span - 1)]
        for idx, s0 in enumerate(starts, 1):
            self._check()
            chunk = items[s0:s0 + span]
            out = work / f"clip_test_{idx}.mkv"
            engine.filter_concat(chunk, out, cfg)
            verify_has_av(engine.ff, out, require_audio=True)
            self.log(f"TEST {idx}: PASS  clips {s0 + 1}-{s0 + span}")
            self._progress(idx / len(starts) * 100.0, stage="tests")

    def _clip_boundaries(self, items) -> list[float]:
        items = sorted(items, key=lambda x: numeric_sort_key(x.path))
        acc, pts = 0.0, []
        for m in items[:-1]:
            acc += m.duration
            pts.append(round(acc, 3))
        if len(pts) > 400:
            stepn = len(pts) / 400.0
            pts = [pts[int(i * stepn)] for i in range(400)]
        return pts

    def _flagged_clip_indices(self, items, flagged: list[float]) -> set[int]:
        items = sorted(items, key=lambda x: numeric_sort_key(x.path))
        edges, acc = [], 0.0
        for m in items[:-1]:
            acc += m.duration
            edges.append(acc)
        idxs: set[int] = set()
        for b in flagged:
            best = min(range(len(edges)), key=lambda i: abs(edges[i] - b)) if edges else 0
            idxs.add(best)
            idxs.add(best + 1)
        return idxs
