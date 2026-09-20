package ca.repere.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Rationale opened from Health Connect's privacy-policy entry point. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF0F5946))){
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text(stringResource(R.string.rationale_title),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                    Text(stringResource(R.string.rationale_why_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.rationale_body))
                    Text(stringResource(R.string.rationale_sent_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.rationale_sent_body))
                    Text(stringResource(R.string.rationale_control_title),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.rationale_control_body))
                    Button(onClick={finish()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.rationale_back))}
                }
            }
        }
    }
}
