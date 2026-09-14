"""End-to-end orchestration with structured events for the UI.

Every major step verifies its output. Two random preflight tests must pass
before full processing. A final boundary/beep scan must reach zero remaining
artifacts before the output is accepted. Source files are never deleted.
All large media intermediates live under <input>\\temp\\<job> so the
processing workspace stays beside the source media and is removed on
success, cancellation, or failure.
"""
from __future__ import annotations

import random
import shutil
import uuid
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
from .exo_detector import (find_cache_index, map_playlist_to_exo,
                           parse_hls_media_playlist, scan_exo_inventory)
from .exo_converter import (ReconResult, build_ts_segments, concat_segments,
                            describe_signature, resolve_order,
                            video_representations, video_sig_from_stream)
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
        # ETA smoothing: track a moving average of overall-progress rate so the
        # estimate reflects the current phase's speed instead of assuming the
        # whole job progresses at the average-since-start rate.
        self._eta_last_t: float | None = None
        self._eta_last_overall: float = 0.0
        self._eta_rate: float | None = None   # %-overall per second (smoothed)
        self._eta_shown: float | None = None

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
        eta = self._estimate_eta(overall, elapsed)
        self.emit({"type": "progress", "stage": stage,
                   "stageName": getattr(self, "_stage_name", ""),
                   "pct": round(overall, 1),
                   "stagePct": round(pct, 1),
                   "speed": (f"{speed:.1f}x" if speed else None),
                   "elapsed": int(elapsed), "eta": (int(eta) if eta and eta > 0 else None),
                   "cur": cur, "total": total})

    def _estimate_eta(self, overall: float, elapsed: float) -> float | None:
        """Rate-based ETA smoothed with an EMA, so it tracks the current phase
        rather than the average-since-start (which swung wildly when a fast
        phase was followed by a slow one). Falls back to the simple estimate
        until a rate is established, and rate-limits how fast the shown value
        can change so it counts down smoothly."""
        now = time.time()
        if overall <= 0:
            self._eta_last_t, self._eta_last_overall = now, overall
            return None
        if self._eta_last_t is not None:
            dt = now - self._eta_last_t
            dp = overall - self._eta_last_overall
            if dt > 0.05 and dp > 0:
                inst = dp / dt                      # %-overall per second
                self._eta_rate = (inst if self._eta_rate is None
                                  else 0.2 * inst + 0.8 * self._eta_rate)
        self._eta_last_t, self._eta_last_overall = now, overall

        if self._eta_rate and self._eta_rate > 1e-6:
            raw = (100.0 - overall) / self._eta_rate
        else:
            raw = elapsed / (overall / 100.0) - elapsed   # fallback
        raw = max(0.0, raw)
        # Smooth the displayed value so it doesn't jump around.
        if self._eta_shown is None:
            self._eta_shown = raw
        else:
            self._eta_shown = 0.3 * raw + 0.7 * self._eta_shown
        return self._eta_shown

    def _ff(self, stage: str):
        def prog(pct, speed):
            self._progress(pct, speed, stage=stage)
        return FF(self.log, prog, self.cancel)

    def _check(self):
        if self.cancel.is_set():
            raise Cancelled("Operation cancelled by user.")

    @staticmethod
    def _input_roots(cfg: Config) -> list[Path]:
        roots: list[Path] = []
        for p in cfg.inputs:
            p = Path(p)
            roots.append(p if p.is_dir() else p.parent)
        return roots

    @classmethod
    def _temp_roots(cls, cfg: Config) -> list[Path]:
        return [root / "temp" for root in cls._input_roots(cfg)]

    @staticmethod
    def _is_within(path: Path, parent: Path) -> bool:
        try:
            path.resolve().relative_to(parent.resolve())
            return True
        except ValueError:
            return False

    @classmethod
    def _filter_processing_workspace(cls, paths: list[Path], cfg: Config) -> list[Path]:
        """Never treat our input\temp workspace as source media."""
        temp_roots = cls._temp_roots(cfg)
        return [p for p in paths
                if not any(cls._is_within(Path(p), t) for t in temp_roots)]

    @classmethod
    def _make_workdir(cls, cfg: Config) -> Path:
        """Create an isolated per-job workspace under the user's input folder(s)."""
        roots = cls._input_roots(cfg)
        if not roots:
            raise RuntimeError("No input folder is available for the processing workspace.")

        root = roots[0].resolve()
        temp_root = root / "temp"
        temp_root.mkdir(parents=True, exist_ok=True)

        # Never allow the final output to be placed inside our disposable workspace.
        output = Path(cfg.output).resolve()
        if cls._is_within(output, temp_root):
            raise RuntimeError(
                "The final output cannot be saved inside the input\\temp workspace. "
                "Choose the input folder, another folder, or another drive for the output."
            )

        stamp = time.strftime("%Y%m%d_%H%M%S")
        for _ in range(10):
            name = f"job_{stamp}_{uuid.uuid4().hex[:8]}"
            work = temp_root / name
            try:
                work.mkdir(parents=False, exist_ok=False)
            except FileExistsError:
                continue

            # Explicit step folders keep the workspace understandable to a user.
            for name in ("reconstruct", "paired", "batches", "tests", "previews", "final"):
                (work / name).mkdir()
            return work

        raise RuntimeError("Could not create a unique processing workspace.")

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
        out = work / "previews" / "preview_cell.jpg"
        self._emit_preview_file(engine.last_frame_jpeg(media, out))

    def _preview_at(self, media: Path, seconds: float, work: Path):
        """Grab a frame at a specific timestamp of a completed source file.

        Used for the live EXO preview: we pull the frame from the already
        reconstructed continuous video at the position currently being written,
        so the preview follows progress without touching the output file. Runs
        on a quiet FF so it neither spams the log nor moves the progress bar.
        """
        qff = FF(lambda *_: None, None, self.cancel)
        out = work / "previews" / "preview_cell.jpg"
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
        paths = self._filter_processing_workspace(collect(cfg.inputs, cfg.recursive), cfg)
        self.log(f"Collected {len(paths)} files from input.")
        exos = [p for p in paths if p.suffix.lower() == EXO_EXT]
        info: dict = {"mode": None}
        if exos:
            inv = scan_exo_inventory(exos, self.log)
            if inv.media_count == 0:
                raise RuntimeError(
                    "The .exo cache contains no playable media objects — only "
                    "playlists / subtitles / unknown files were found. Nothing to reconstruct.")
            pl = inv.best_media_playlist()
            pinfo = parse_hls_media_playlist(pl) if pl else None
            info = {
                "mode": "EXO-TS" if inv.ts_media else "EXO",
                "exo_files": inv.total,
                "ts_media": len(inv.ts_media),
                "fmp4_media": len(inv.fmp4_media),
                "playlists": len(inv.hls_master) + len(inv.hls_media),
                "subtitles": len(inv.webvtt),
                "unknown": len(inv.unknown),
                "playlist_segments": (pinfo or {}).get("segment_count"),
                "batches": batch_count(inv.media_count),
            }
            if inv.ts_media and inv.fmp4_media:
                info["warning"] = ("Both MPEG-TS and fragmented-MP4 media are present. "
                                   "Reconstruction will select the single coherent "
                                   "rendition and report if they differ.")
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
        work = self._make_workdir(cfg)
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

            paths = self._filter_processing_workspace(collect(cfg.inputs, cfg.recursive), cfg)
            exos = [p for p in paths if p.suffix.lower() == EXO_EXT]
            if exos:
                inv = scan_exo_inventory(exos, self.log)
                self._run_exo_cache(ff, cfg, inv, paths, exos, work)
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
            # A cancelled job must not leave the large input\\temp media workspace
            # behind. Keep the diagnostic text in the UI, then remove the whole
            # per-job temporary directory.
            self.status("CANCELLED")
            log_text = (
                log_path.read_text(encoding="utf-8", errors="replace")
                if log_path.exists() else "Operation cancelled by user."
            )
            shutil.rmtree(work, ignore_errors=True)
            self.emit({
                "type": "cancelled",
                "work": str(work),
                "logText": log_text,
            })
        except Exception as e:
            # Failed jobs also clean their input\\temp media workspace. The UI gets
            # the complete diagnostic text before the temporary directory is
            # removed, so multi-GB intermediates cannot accumulate in %TEMP%.
            self.status("FAILED")
            log_text = (
                log_path.read_text(encoding="utf-8", errors="replace")
                if log_path.exists() else str(e)
            )
            work_path = str(work)
            shutil.rmtree(work, ignore_errors=True)
            self.emit({
                "type": "error",
                "stage": getattr(e, "stage", None) or "PROCESSING",
                "problem": str(e),
                "work": work_path,
                "log": "",
                "logText": log_text,
                "tempCleaned": True,
            })

    # -- Unified EXO reconstruction (format-agnostic front end) -----------
    def _run_exo_cache(self, ff, cfg, inv, paths, exos, work):
        """Format-agnostic entry point. Classifies each object (done), probes a
        common MediaSegment model, groups by real rendition signature, selects
        ONE coherent rendition, then routes it through the common adapter
        interface (`_reconstruct_representation`) — MPEG-TS and fMP4 each return
        the same ReconResult — and finally through the ONE shared finalize
        stage. A normalized media abstraction, not mandatory per-segment MP4s:
        each adapter uses the lossless continuous assembly correct for its
        container to keep audio gapless."""
        if inv.media_count == 0:
            raise RuntimeError(
                "The .exo cache contains no playable media objects — only "
                "playlists / subtitles / unknown files were found. Nothing to reconstruct.")

        root = cfg.inputs[0] if cfg.inputs and cfg.inputs[0].is_dir() else exos[0].parent
        pl = inv.best_media_playlist()
        pinfo = parse_hls_media_playlist(pl) if pl else None

        self.status("BUILDING")
        self.step("exo", "EXO INGESTION / MODEL")
        self._band(0, 25)

        # 1) Probe every MPEG-TS object into the segment model (hard-fail on any
        #    unreadable required object). fMP4 media are represented by their
        #    init signature (their fragments are not independently decodable, so
        #    the proven continuous engine reconstructs them).
        ts_segments = build_ts_segments(ff, inv.ts_media, self.log) if inv.ts_media else []
        ts_video_groups = video_representations(ts_segments)

        fmp4_sig = None
        fmp4_scan = None
        if inv.fmp4_media:
            try:
                fmp4_scan = scan_exo(root, self.log)
                vinfo = ff.probe(fmp4_scan.video_init)
                vstream = next((s for s in vinfo.get("streams", [])
                                if s.get("codec_type") == "video"), None)
                acodec = None
                try:
                    ainfo = ff.probe(fmp4_scan.audio_init)
                    astream = next((s for s in ainfo.get("streams", [])
                                    if s.get("codec_type") == "audio"), None)
                    acodec = (astream or {}).get("codec_name")
                except Exception:
                    acodec = None
                if vstream:
                    fmp4_sig = video_sig_from_stream(vstream, acodec)
            except Exception as e:
                raise RuntimeError(
                    f"Fragmented-MP4 media is present but could not be read to "
                    f"determine its rendition:\n  reason: {e}")

        # 2) Build representations keyed by a FULL rendition signature
        #    (video codec, width, height, fps, audio codec). Same signature ⇒
        #    same logical rendition; different signatures ⇒ genuinely different
        #    renditions (e.g. adaptive bitrates), never mixed together.
        reps: list[dict] = []
        for sig, segs in ts_video_groups.items():
            reps.append({"source": "MPEG-TS", "sig": sig,
                         "count": len(segs), "payload": segs})
        if fmp4_sig is not None:
            reps.append({"source": "fMP4", "sig": fmp4_sig,
                         "count": len(inv.fmp4_media), "payload": fmp4_scan})
        audio_only_ts = [s for s in ts_segments if s.media_type == "audio_only"]

        if not reps:
            if audio_only_ts:
                raise RuntimeError(
                    "This cache contains only audio segments (no video track). "
                    "A combined A/V movie cannot be reconstructed from it.")
            raise RuntimeError("No usable video media was found in the cache.")

        # 3) Select ONE coherent logical rendition (property + manifest based).
        rep = self._resolve_representation(reps, pinfo)
        self.emit({"type": "detect", "info": {
            "mode": "EXO-TS" if rep["source"] == "MPEG-TS" else "EXO-fMP4",
            "exo_files": inv.total,
            "ts_media": len(inv.ts_media), "fmp4_media": len(inv.fmp4_media),
            "playlists": len(inv.hls_master) + len(inv.hls_media),
            "subtitles": len(inv.webvtt), "unknown": len(inv.unknown),
            "playlist_segments": (pinfo or {}).get("segment_count"),
            "representation": describe_signature(rep["sig"], rep["source"], rep["count"])}})
        self.step("exo", "EXO INGESTION / MODEL", "done")

        # 4) COMMON adapter interface. Every format returns the SAME ReconResult
        #    contract; the format-specific internals (continuous TS assembly /
        #    ExoEngine fMP4 reconstruction) live behind it.
        result = self._reconstruct_representation(
            ff, rep, pinfo, root, inv, audio_only_ts, work)

        # 5) ONE common finalize stage for EVERY EXO format.
        self._finalize_continuous(ff, cfg, result, work)

    # -- representation resolution ----------------------------------------
    def _resolve_representation(self, reps, pinfo):
        """Select ONE coherent logical rendition from real media properties and
        manifest evidence. It never asks the user to delete a format and never
        picks 'highest resolution' blindly:

        * One signature → coherent. If it exists in several packagings (TS +
          fMP4), auto-select one source.
        * Multiple signatures (genuinely different renditions, e.g. adaptive
          bitrates of one asset) → prefer the rendition the HLS playlist
          corroborates; only if none is corroborated fall back to the
          highest-quality one, logging the basis and every alternative.
        `reps` entries are dicts: {source, sig, count, payload}."""
        by_sig: dict = {}
        for e in reps:
            by_sig.setdefault(e["sig"], []).append(e)

        if len(by_sig) == 1:
            entries = next(iter(by_sig.values()))
        else:
            plc = (pinfo or {}).get("segment_count")
            corr = {e["sig"] for elist in by_sig.values() for e in elist
                    if plc and e["source"] == "MPEG-TS" and e["count"] == plc}
            if len(corr) == 1:
                sig = next(iter(corr))
                basis = "HLS-playlist corroboration"
            else:
                def quality(s):
                    return ((s[1] or 0) * (s[2] or 0), s[3] or 0)   # (area, fps)
                sig = max(by_sig, key=quality)
                basis = "highest-quality rendition (no single playlist-corroborated one)"
            entries = by_sig[sig]
            self.log(f"Multiple renditions detected; selected by {basis}:")
            self.log("  selected: " + describe_signature(
                entries[0]["sig"], entries[0]["source"], entries[0]["count"]))
            for s, elist in by_sig.items():
                if s != sig:
                    for e in elist:
                        self.log("  not used: " + describe_signature(
                            e["sig"], e["source"], e["count"]))

        if len(entries) == 1:
            return entries[0]

        # Same rendition, multiple packagings (TS + fMP4) → pick one source.
        ts_e = next((e for e in entries if e["source"] == "MPEG-TS"), None)
        fmp4_e = next((e for e in entries if e["source"] == "fMP4"), None)
        if ts_e and pinfo and pinfo.get("segment_count") == ts_e["count"]:
            chosen, why = ts_e, "MPEG-TS segment count matches the HLS playlist (verifiably complete)"
        elif fmp4_e:
            chosen, why = fmp4_e, "fragmented-MP4 continuous packaging"
        else:
            chosen, why = entries[0], "first available source"
        self.log(f"The same rendition is present in multiple packagings "
                 f"({', '.join(e['source'] for e in entries)}); automatically "
                 f"selected {chosen['source']} — {why}. Source files are untouched.")
        return chosen

    # -- common adapter interface -----------------------------------------
    def _reconstruct_representation(self, ff, rep, pinfo, root, inv,
                                    audio_only_ts, work) -> ReconResult:
        """The single interface both formats implement. Dispatches to the
        format-specific adapter, each of which returns the shared ReconResult
        (video, audio, duration, boundaries, metadata)."""
        if rep["source"] == "MPEG-TS":
            return self._adapt_ts(ff, rep, pinfo, root, inv, audio_only_ts, work)
        if rep["source"] == "fMP4":
            return self._adapt_fmp4(ff, rep["payload"], work)
        raise RuntimeError(f"Unsupported representation source: {rep['source']}")

    def _adapt_ts(self, ff, rep, pinfo, root, inv, audio_only_ts, work) -> ReconResult:
        """MPEG-TS adapter: order the normalized MediaSegments, then assemble a
        continuous lossless transport stream (the internal optimization that
        keeps audio gapless) and return the common ReconResult contract."""
        video_bearing = rep["payload"]
        types = {s.media_type for s in video_bearing}
        cache_index = find_cache_index(root)
        mapping = map_playlist_to_exo(pinfo, inv.ts_media, cache_index, self.log)
        # A combined A/V representation already contains its own audio. Any
        # separate audio-only EXOs belong to another layout/rendition unless
        # explicitly selected, so they must never be appended to the video list.
        if types == {"combined_av"}:
            ordered = resolve_order(video_bearing, pinfo, mapping, self.log)
            return self._ts_continuous(ff, ordered, ordered, pinfo, work)

        if types == {"video_only"}:
            selected_audio_codec = rep["sig"][4] if len(rep["sig"]) >= 5 else None
            if selected_audio_codec is None:
                raise RuntimeError(
                    "The selected video-only MPEG-TS rendition does not declare an "
                    "audio codec, so automatic audio pairing would be unsafe.")

            matching_audio = [
                s for s in audio_only_ts
                if s.audio_codec == selected_audio_codec
            ]
            if not matching_audio:
                found = sorted({s.audio_codec or "unknown" for s in audio_only_ts})
                raise RuntimeError(
                    "The selected MPEG-TS video rendition has no matching "
                    f"audio-only stream. Expected audio codec: {selected_audio_codec}; "
                    f"found: {', '.join(found) if found else 'none'}. "
                    "Reconstruction stopped rather than mix incompatible audio.")

            # A separate audio stream is usable only when it forms exactly one
            # segment-for-segment timeline for the selected video rendition.
            # Never merge extra/duplicate audio objects merely because their codec
            # matches; that could create an invalid or duplicated soundtrack.
            if len(matching_audio) != len(video_bearing):
                raise RuntimeError(
                    "Separate MPEG-TS video/audio segment counts do not match for "
                    f"the selected rendition ({len(video_bearing)} video vs "
                    f"{len(matching_audio)} matching audio). Refusing to guess which "
                    "audio objects belong to the movie.")

            v = resolve_order(video_bearing, pinfo, mapping, self.log)
            a = resolve_order(matching_audio, None, None, self.log)

            # The separate streams must cover approximately the same timeline.
            if v and a and v[0].timeline_pos() is not None and a[0].timeline_pos() is not None:
                start_delta = abs(v[0].timeline_pos() - a[0].timeline_pos())
                if start_delta > 1.0:
                    raise RuntimeError(
                        "Separate MPEG-TS video/audio timelines do not align: "
                        f"start offset is {start_delta:.3f}s.")
            if v and a:
                for i, (vs, aa) in enumerate(zip(v, a)):
                    vd = vs.duration or 0.0
                    ad = aa.duration or 0.0
                    if vd and ad and abs(vd - ad) > 0.75:
                        raise RuntimeError(
                            "Separate MPEG-TS video/audio segment durations do not "
                            f"match at segment #{i + 1} ({vd:.3f}s vs {ad:.3f}s).")

            return self._ts_continuous(ff, v, a, pinfo, work)

        raise RuntimeError(
            "The selected MPEG-TS rendition has an unsupported or mixed media layout: "
            f"{sorted(types)}. Refusing to mix combined, video-only, and audio-only "
            "segments because that could produce a broken movie.")

    def _ts_continuous(self, ff, v_segments, a_segments, pinfo, work) -> ReconResult:
        """MPEG-TS internal assembly (behind the common adapter).

        Concatenate the ordered segment bytes into one continuous transport
        stream (gapless: no per-segment MP4/AAC decoder restarts), verify it,
        and return the shared ReconResult. `v_segments is a_segments` ⇒ combined
        A/V in one file; otherwise separate video/audio renditions."""
        combined = v_segments is a_segments
        rec = work / "reconstruct"
        srcs = [s.source for s in v_segments] + ([] if combined else [s.source for s in a_segments])
        self._preflight_space(work, self._sum_sizes(srcs), "reconstruction workspace")

        self.status("BUILDING")
        self.step("exo2", "CONTINUOUS RECONSTRUCTION (MPEG-TS)")
        self._band(0, 45)

        def cat_prog(done, total):
            self._progress((done / total * 100.0) if total else 0.0,
                           cur=done, total=total, stage="exo2")

        if combined:
            av = concat_segments(v_segments, rec / "continuous_av.ts", self.log, on_progress=cat_prog)
            video = audio = av
        else:
            video = concat_segments(v_segments, rec / "continuous_video.ts", self.log, on_progress=cat_prog)
            audio = concat_segments(a_segments, rec / "continuous_audio.ts", self.log, on_progress=cat_prog)
        self._check()

        vsum = stream_summary(ff, video)
        asum = stream_summary(ff, audio)
        if not vsum.get("video"):
            raise RuntimeError("Continuous reconstruction produced no video stream.")
        if not asum.get("audio"):
            raise RuntimeError("Continuous reconstruction produced no audio stream.")
        vd = vsum["duration"] or 0.0
        ad = asum["duration"] or 0.0
        dur = min(vd, ad) if (vd and ad) else (vd or ad)
        self.log(f"Continuous video {human_seconds(vd)} | continuous audio {human_seconds(ad)}")
        if pinfo and pinfo.get("total_duration") and dur > 0:
            exp = pinfo["total_duration"]
            if abs(dur - exp) > max(2.0, 0.02 * exp):
                self.log(f"NOTE: reconstructed duration {human_seconds(dur)} differs "
                         f"from HLS playlist total {human_seconds(exp)}.")
        self.step("exo2", "CONTINUOUS RECONSTRUCTION (MPEG-TS)", "done")
        self._progress(100.0)
        self._preview_at(video, max(1.0, dur * 0.02), work)
        boundaries = self._ts_boundaries(a_segments if not combined else v_segments, dur)
        return ReconResult(video=video, audio=audio, duration=dur, boundaries=boundaries,
                           metadata={"source": "MPEG-TS", "combined": combined,
                                     "segments": len(v_segments)})

    def _adapt_fmp4(self, ff, scan, work) -> ReconResult:
        """fMP4 adapter. The proven ExoEngine remains the specialized
        fragmented-MP4 parser/reconstructor; its continuous output is returned
        through the SAME ReconResult contract as the TS adapter, so both share
        one finalize stage (no bypass)."""
        self.status("BUILDING")
        self.step("exo2", "CONTINUOUS RECONSTRUCTION (fMP4)")
        self._band(0, 45)
        frag_bytes = self._sum_sizes([p for _, p in scan.video_fragments]
                                     + [p for _, p in scan.audio_fragments]
                                     + [scan.video_init, scan.audio_init])
        self._preflight_space(work, frag_bytes, "reconstruction workspace")

        def recon_prog(done, total):
            self._progress(done / total * 100.0 if total else 0.0,
                           cur=done, total=total, stage="exo2")

        video, audio = ExoEngine(ff, self.log).reconstruct(
            scan, work / "reconstruct", on_progress=recon_prog)
        self._check()
        vs = stream_summary(ff, video)
        as_ = stream_summary(ff, audio)
        vd, ad = vs["duration"], as_["duration"]
        self.log(f"Continuous video {human_seconds(vd)} | continuous audio {human_seconds(ad)}")
        self.step("exo2", "CONTINUOUS RECONSTRUCTION (fMP4)", "done")
        self._progress(100.0)
        self._preview_at(video, max(1.0, min(vd, ad) * 0.02), work)
        dur = min(vd, ad)
        boundaries = self._exo_boundaries(scan, ad)
        return ReconResult(video=video, audio=audio, duration=dur, boundaries=boundaries,
                           metadata={"source": "fMP4",
                                     "combined": video == audio,
                                     "fragments": len(scan.video_fragments)})

    def _finalize_continuous(self, ff, cfg, result: ReconResult, work):
        """The ONE common finalize stage shared by EVERY EXO format. Consumes
        the ReconResult contract (video, audio, duration, boundaries): two
        random preflight tests → single remux (COPY FIRST) or re-encode
        (NORMALIZE ALL) → audio-integrity check → boundary/beep scan. Being
        MPEG-TS at source never forces re-encoding: COPY FIRST stream-copies the
        original H.264/AAC."""
        video, audio, dur, boundaries = (result.video, result.audio,
                                         result.duration, result.boundaries)
        self.status("TESTING")
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS")
        self._band(45, 8)
        self._exo_tests(ff, video, audio, dur, work)
        self.step("tests", "TWO RANDOM PREFLIGHT TESTS", "done")

        out_est = self._sum_sizes(list({video, audio}))
        self._preflight_space(cfg.output.parent, out_est, "final output")
        self.status("BUILDING")
        self.step("mux", "FINAL OUTPUT")
        self._band(53, 34)
        last_prev = [time.time()]

        def mux_prog(pct, speed):
            self._progress(pct, speed, stage="mux")
            now = time.time()
            if cfg.quality == "normalize_all" and dur > 0 and now - last_prev[0] > 6.0:
                last_prev[0] = now
                self._preview_at(video, dur * max(0.0, min(1.0, pct / 100.0)), work)

        ExoEngine(FF(self.log, mux_prog, self.cancel), self.log).mux(
            video, audio, cfg.output, cfg, duration=dur)
        self._progress(100.0)
        self._preview_at(video, dur * 0.98, work)

        osum = stream_summary(ff, cfg.output)
        if not osum.get("audio"):
            raise RuntimeError(
                "Merged output has video but NO audio stream. Output rejected.")
        adur = float((osum.get("audio") or {}).get("duration") or 0) or osum.get("duration", 0)
        if osum.get("duration", 0) > 0 and adur < 0.5 * osum["duration"]:
            raise RuntimeError(
                f"Merged audio is far shorter than video ({adur:.0f}s vs "
                f"{osum['duration']:.0f}s) — the merge is likely broken. Output rejected.")
        self.log(f"Audio-integrity OK · {describe(osum)}")
        self.step("mux", "FINAL OUTPUT", "done")

        self.status("VERIFYING")
        self.step("beep", "BOUNDARY / BEEP SCAN")
        self._band(87, 10)
        result = beep.scan_boundaries(ff, cfg.output, boundaries, self.log,
                                      on_progress=lambda p: self._progress(p, stage="beep"))
        self.emit({"type": "beep", "scanned": result["scanned"], "clean": result["clean"],
                   "suspicious": result["suspicious"], "repaired": 0,
                   "remaining": result["suspicious"]})
        if result["suspicious"] > 0:
            raise RuntimeError(
                f"Boundary scan found {result['suspicious']} suspicious seam(s). "
                "The cache may be incomplete. Output was not accepted.")
        self.log("Boundary scan clean. Continuous-stream path avoids per-segment "
                 "decoder restarts.")
        self.step("beep", "BOUNDARY / BEEP SCAN", "done")

    def _ts_boundaries(self, segments, dur: float) -> list[float]:
        """Segment-seam times from cumulative durations, sampled + random regions."""
        pts, acc = [], 0.0
        for s in segments[:-1]:
            acc += (s.duration or 0.0)
            if 0.2 < acc < dur - 0.2:
                pts.append(round(acc, 3))
        pts = sorted(set(pts))
        if len(pts) > 120:
            stepn = len(pts) / 120.0
            pts = [pts[int(i * stepn)] for i in range(120)]
        for _ in range(8):
            pts.append(round(random.uniform(0.2, max(0.3, dur - 0.2)), 3))
        return sorted(set(pts))

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
            d = work / "tests" / f"test_{idx}"
            d.mkdir(parents=False, exist_ok=False)
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
        master = work / "final" / "master.mkv"
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
            master2 = work / "final" / "master_repaired.mkv"
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
            out = work / "tests" / f"clip_test_{idx}.mkv"
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
