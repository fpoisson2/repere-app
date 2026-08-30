package ca.repere.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private val SOCIAL = listOf(
    "alone" to R.string.social_alone, "partner_family" to R.string.social_partner_family,
    "friends" to R.string.social_friends, "colleagues_event" to R.string.social_colleagues_event,
)
private val OTHERS = listOf("no" to R.string.answer_no, "yes" to R.string.answer_yes, "unknown" to R.string.answer_unknown)

@Composable
fun CheckInDialog(day: LocalDate, onDismiss: () -> Unit, onSubmit: (JSONObject) -> Unit) {
    var craving by remember { mutableFloatStateOf(3f) }
    var confidence by remember { mutableFloatStateOf(7f) }
    var stress by remember { mutableFloatStateOf(3f) }
    var plannedStandards by remember { mutableStateOf("0") }
    var social by remember { mutableStateOf("alone") }
    var others by remember { mutableStateOf("no") }
    var available by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.checkin)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Slider0to10(stringResource(R.string.checkin_craving), craving) { craving = it }
                Slider0to10(stringResource(R.string.checkin_confidence), confidence) { confidence = it }
                Slider0to10(stringResource(R.string.checkin_stress), stress) { stress = it }
                OutlinedTextField(
                    plannedStandards, { plannedStandards = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text(stringResource(R.string.checkin_planned_label)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(stringResource(R.string.checkin_social_context), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    SOCIAL.forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = social == value, onClick = { social = value })
                            Text(stringResource(label))
                        }
                    }
                }
                Text(stringResource(R.string.checkin_others_drinking), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Row {
                    OTHERS.forEach { (value, label) ->
                        FilterChip(selected = others == value, onClick = { others = value }, label = { Text(stringResource(label)) }, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.checkin_alcohol_available), Modifier.weight(1f))
                    Switch(checked = available, onCheckedChange = { available = it })
                }
                OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text(stringResource(R.string.checkin_notes)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSubmit(
                    JSONObject()
                        .put("observed_at", OffsetDateTime.now().toString())
                        .put("local_date", day.toString())
                        .put("timezone_id", ZoneId.systemDefault().id)
                        .put("craving", craving.roundToInt())
                        .put("confidence", confidence.roundToInt())
                        .put("stress", stress.roundToInt())
                        .put("planned_grams", ((plannedStandards.replace(',', '.').toDoubleOrNull() ?: 0.0) * 13.45).coerceIn(0.0, 1000.0))
                        .put("social_context", social)
                        .put("others_drinking", others)
                        .put("alcohol_available", available)
                        .apply { if (notes.isNotBlank()) put("notes", notes) },
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun Slider0to10(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(stringResource(R.string.slider_value, label, value.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(value = value, onValueChange = onChange, valueRange = 0f..10f, steps = 9)
    }
}
