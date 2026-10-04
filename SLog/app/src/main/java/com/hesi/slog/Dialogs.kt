package com.hesi.slog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Simple YES / NO confirmation. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    font: FontFamily,
    onYes: () -> Unit,
    onNo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onNo,
        confirmButton = {
            Button(onClick = onYes, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("YES", fontFamily = font, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onNo) {
                Text("NO", color = Color.LightGray, fontFamily = font)
            }
        },
        title = { Text(title, color = Color.White, fontFamily = font) },
        text = { Text(message, color = Color.LightGray) },
        containerColor = DialogBackground
    )
}

/** Shows every log read from a .csv / .slog file; the user picks which ones to add. */
@Composable
fun CsvPreviewDialog(
    rows: List<CsvRow>,
    font: FontFamily,
    onImport: (rows: List<CsvRow>, includeHistory: Boolean) -> Unit,
    onCancel: () -> Unit
) {
    // Valid, non-duplicate rows start selected
    var selected by remember(rows) {
        mutableStateOf(rows.filter { it.isValid && !it.isDuplicate }.map { it.lineNumber }.toSet())
    }
    var includeHistory by remember(rows) { mutableStateOf(false) }
    val chosen = rows.filter { it.lineNumber in selected }
    val historyDays = chosen.sumOf { it.history.size }
    val fileHasHistory = rows.any { it.history.isNotEmpty() }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(
                onClick = { onImport(chosen, includeHistory && fileHasHistory) },
                enabled = chosen.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("ADD ${chosen.size} LOG" + if (chosen.size == 1) "" else "S", fontFamily = font, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("CANCEL", color = Color.LightGray, fontFamily = font)
            }
        },
        title = { Text("IMPORT PREVIEW", color = Color.White, fontFamily = font) },
        text = {
            Column {
                Text(
                    "${rows.size} log(s) found. Untick anything you don't want.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                if (fileHasHistory) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeHistory = !includeHistory },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeHistory,
                            onCheckedChange = { includeHistory = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Accent,
                                uncheckedColor = Color.Gray,
                                checkmarkColor = Color.White
                            )
                        )
                        Text(
                            "Also import tick history ($historyDays days) as my ticks",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(rows, key = { it.lineNumber }) { row ->
                        val isOn = row.lineNumber in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1A1A1A))
                                .padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isOn,
                                enabled = row.isValid,
                                onCheckedChange = { on ->
                                    selected = if (on) selected + row.lineNumber else selected - row.lineNumber
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Accent,
                                    uncheckedColor = Color.Gray,
                                    checkmarkColor = Color.White
                                )
                            )
                            Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(
                                    text = row.name.ifBlank { "(no name) - #${row.lineNumber}" }.uppercase(),
                                    color = if (row.isValid) Color.White else Color.Gray,
                                    fontFamily = font
                                )
                                Text(
                                    text = if (row.times.isEmpty()) "No times"
                                    else row.times.joinToString("  ") { DateUtils.formatTo12Hour(it) },
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                                if (row.history.isNotEmpty()) {
                                    Text("${row.history.size} days of history", color = Color.Gray, fontSize = 11.sp)
                                }
                                row.problems.forEach { p ->
                                    Text(p, color = Accent, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Accepts .csv (name,times  e.g.  Gym,\"07:00;19:00\") and .slog files.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        },
        containerColor = DialogBackground
    )
}

/** Share options for one log or all logs: CSV, .slog, or both files. */
@Composable
fun ExportDialog(
    title: String,
    font: FontFamily,
    onPick: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = Color.LightGray, fontFamily = font)
            }
        },
        title = { Text(title, color = Color.White, fontFamily = font) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Send as a file. Your friend opens S Log, taps IMPORT and picks the file.",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                ExportOption("CSV", "Name + times. Also opens in Excel / Google Sheets.", font) {
                    onPick(ExportFormat.CSV)
                }
                ExportOption(".SLOG", "S Log file: names, times and your tick history.", font) {
                    onPick(ExportFormat.SLOG)
                }
                ExportOption("BOTH", "Sends the .csv and the .slog file together.", font) {
                    onPick(ExportFormat.BOTH)
                }
            }
        },
        containerColor = DialogBackground
    )
}

@Composable
private fun ExportOption(label: String, description: String, font: FontFamily, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1C1C1C))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = Accent, fontFamily = font, fontSize = 16.sp)
        Text(description, color = Color.LightGray, fontSize = 12.sp)
    }
}

/** Opened from the avatar: who is signed in, sign out, delete account. */
@Composable
fun ProfileDialog(
    name: String,
    email: String,
    initials: String,
    font: FontFamily,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = Accent, fontFamily = font)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(initials, color = Color.White, fontFamily = font, fontSize = 26.sp)
                }
                Text(name.uppercase(), color = Color.White, fontFamily = font, fontSize = 18.sp, textAlign = TextAlign.Center)
                Text(email, color = Color.Gray, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onSignOut,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                ) {
                    Text("SIGN OUT", color = Color.White, fontFamily = font, fontSize = 12.sp)
                }
                Button(
                    onClick = onDeleteAccount,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A0A12))
                ) {
                    Text("DELETE ACCOUNT", color = Accent, fontFamily = font, fontSize = 12.sp)
                }
            }
        },
        containerColor = DialogBackground
    )
}

/** Asks for the password, then deletes the account and all its logs for good. */
@Composable
fun DeleteAccountDialog(
    font: FontFamily,
    busy: Boolean,
    error: String?,
    onDelete: (password: String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            Button(
                onClick = { onDelete(password) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("DELETE", fontFamily = font, color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy) {
                Text("CANCEL", color = Color.LightGray, fontFamily = font)
            }
        },
        title = { Text("DELETE ACCOUNT?", color = Color.White, fontFamily = font) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This permanently deletes your account, all logs you created and all their ticks, " +
                            "on every device. It can't be undone. Export your logs first if you want to keep them.",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    label = { Text("Your password", color = Color.LightGray, fontFamily = font) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent)
                )
                if (error != null) Text(error, color = Accent, fontSize = 12.sp)
            }
        },
        containerColor = DialogBackground
    )
}
