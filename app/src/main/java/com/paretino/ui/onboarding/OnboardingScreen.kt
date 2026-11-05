package com.paretino.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paretino.data.WallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val walls: WallRepository) : ViewModel() {
    fun finish(name: String, width: Int, height: Int, climber: String) = viewModelScope.launch {
        walls.setUp(name, width, height, climber)
    }
}

/** First run: what the wall is and who is climbing. The app switches to Home once the wall exists. */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    var name by rememberSaveable { mutableStateOf("My wall") }
    var width by rememberSaveable { mutableStateOf("400") }
    var height by rememberSaveable { mutableStateOf("300") }
    var climber by rememberSaveable { mutableStateOf("") }
    val w = width.toIntOrNull()?.takeIf { it in 50..5000 }
    val h = height.toIntOrNull()?.takeIf { it in 50..5000 }
    val valid = name.isNotBlank() && climber.isNotBlank() && w != null && h != null

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Paretino", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Photograph your wall, let Paretino find the holds, and set problems by tapping them.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Your wall", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                width, { width = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Width (cm)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = w == null,
            )
            OutlinedTextField(
                height, { height = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Height (cm)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = h == null,
            )
        }
        Text(
            "The size straightens photos taken at an angle, so holds line up between set-ups.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("You", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(climber, { climber = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true)
        Button(
            onClick = { vm.finish(name.trim(), w!!, h!!, climber.trim()) },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) { Text("Start") }
    }
}
