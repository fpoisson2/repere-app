package ca.repere.mobile

import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** First-launch welcome flag, separate from every other synced preference — purely local UI state. */
object OnboardingPrefs {
    private const val PREF_SEEN = "onboarding_seen"
    private fun prefs(context: Context) = context.getSharedPreferences("repere", Context.MODE_PRIVATE)
    fun hasSeenOnboarding(context: Context) = prefs(context).getBoolean(PREF_SEEN, false)
    fun setSeenOnboarding(context: Context) {
        prefs(context).edit().putBoolean(PREF_SEEN, true).apply()
    }
}

private data class OnboardingStep(val icon: ImageVector, val titleRes: Int, val bodyRes: Int)

private val ONBOARDING_STEPS = listOf(
    OnboardingStep(Icons.Filled.LocalBar, R.string.onboarding_track_title, R.string.onboarding_track_body),
    OnboardingStep(Icons.Filled.MonitorHeart, R.string.onboarding_bac_title, R.string.onboarding_bac_body),
    OnboardingStep(Icons.Filled.Sync, R.string.onboarding_offline_title, R.string.onboarding_offline_body),
)

/** Three-card welcome flow shown once on first launch, before the account/drink data ever loads. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val lastStep = index == ONBOARDING_STEPS.lastIndex
    Column(Modifier.fillMaxSize().background(Paper).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Crossfade(targetState = index, animationSpec = tween(280), label = "onboardingStep") { i ->
                val s = ONBOARDING_STEPS[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(s.icon, null, tint = Pine, modifier = Modifier.size(40.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(stringResource(s.titleRes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(s.bodyRes), style = MaterialTheme.typography.bodyLarge, color = Pine.copy(alpha = .72f),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ONBOARDING_STEPS.indices.forEach { i ->
                Box(Modifier.size(if (i == index) 10.dp else 8.dp).background(if (i == index) Pine else Pine.copy(alpha = .25f), CircleShape))
            }
        }
        Button(
            onClick = { if (lastStep) onDone() else index++ },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text(stringResource(if (lastStep) R.string.onboarding_start else R.string.onboarding_next)) }
    }
}
