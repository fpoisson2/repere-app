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
                    Text("Repère et tes données de santé",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                    Text("Pourquoi ces autorisations",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("Repère rapproche tes consommations de résumés quotidiens comme le sommeil, les pas, l’exercice et les tendances cardiaques afin de montrer tes propres associations dans le temps.")
                    Text("Ce qui est envoyé",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("Seulement des agrégats par journée et des indicateurs de couverture. Les mesures cardiaques brutes et les séances détaillées ne sont pas transférées au serveur.")
                    Text("Ton contrôle",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    Text("Chaque catégorie est facultative. Tu peux retirer une autorisation dans Health Connect et arrêter les imports depuis l’écran Santé de Repère.")
                    Button(onClick={finish()},modifier=Modifier.fillMaxWidth()){Text("Revenir à Repère")}
                }
            }
        }
    }
}
