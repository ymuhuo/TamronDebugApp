package com.bominwell.tamrondebug.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun PressControlButton(
    text: String,
    modifier: Modifier = Modifier,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val active = remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!enabled && active.value) {
            active.value = false
            onPressEnd()
        } else if (enabled && pressed && !active.value) {
            active.value = true
            onPressStart()
        } else if ((!pressed || !enabled) && active.value) {
            active.value = false
            onPressEnd()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (active.value) {
                active.value = false
                onPressEnd()
            }
        }
    }

    Button(
        modifier = modifier,
        interactionSource = interactionSource,
        enabled = enabled,
        onClick = {}
    ) {
        Text(text)
    }
}

@Composable
fun IntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChangeFinished: (Int) -> Unit,
    valueText: (Int) -> String = { it.toString() }
) {
    var sliderValue by remember(value, range.first, range.last) {
        mutableStateOf(value.coerceIn(range.first, range.last).toFloat())
    }
    val currentValue = sliderValue.roundToInt().coerceIn(range.first, range.last)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${valueText(currentValue)}")
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChangeFinished(currentValue) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun SteppedIntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    onChangeFinished: (Int) -> Unit
) {
    val safeStep = step.coerceAtLeast(1)
    val minUnit = ((range.first + safeStep - 1) / safeStep).coerceAtLeast(0)
    val maxUnit = (range.last / safeStep).coerceAtLeast(minUnit)
    val valueUnit = ((value + safeStep / 2) / safeStep).coerceIn(minUnit, maxUnit)
    var sliderValue by remember(valueUnit, minUnit, maxUnit) {
        mutableStateOf(valueUnit.toFloat())
    }
    val currentUnit = sliderValue.roundToInt().coerceIn(minUnit, maxUnit)
    val currentValue = currentUnit * safeStep

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label: $currentValue")
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChangeFinished(currentValue) },
            valueRange = minUnit.toFloat()..maxUnit.toFloat(),
            steps = (maxUnit - minUnit - 1).coerceAtLeast(0)
        )
    }
}

@Composable
fun TextInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        modifier = modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
fun TwoButtons(
    left: String,
    right: String,
    onLeft: () -> Unit,
    onRight: () -> Unit
) {
    Row {
        Button(onClick = onLeft, modifier = Modifier.weight(1f)) { Text(left) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRight, modifier = Modifier.weight(1f)) { Text(right) }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewControlWidgets() {
    Surface {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionCard("Preview Widgets") {
                TextInputRow(label = "Input", value = "Sample Text", onValueChange = {})
                Spacer(Modifier.height(8.dp))
                TwoButtons(left = "Left", right = "Right", onLeft = {}, onRight = {})
                Spacer(Modifier.height(8.dp))
                IntSliderRow(label = "Slider", value = 50, range = 0..100, onChangeFinished = {})
                Spacer(Modifier.height(8.dp))
                SteppedIntSliderRow(label = "Stepped Slider", value = 4096, range = 1024..10240, step = 1024, onChangeFinished = {})
                Spacer(Modifier.height(8.dp))
                Row {
                    PressControlButton(text = "Press and Hold", onPressStart = {}, onPressEnd = {})
                }
            }
        }
    }
}
