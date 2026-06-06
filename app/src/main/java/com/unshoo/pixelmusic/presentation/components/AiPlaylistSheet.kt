package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.ui.theme.ExpTitleTypography
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiPlaylistSheet(
    onDismiss: () -> Unit,
    onGenerateClick: (
        mode: String,
        count: Int,
        timePeriod: String,
        seedTrackName: String,
        seedArtistName: String,
        seedArtistInput: String,
        tagInput: String
    ) -> Unit,
    isGenerating: Boolean,
    isSuccess: Boolean,
    status: String?,
    error: String?,
    onRetry: () -> Unit
) {
    var selectedMode by remember { mutableStateOf("recommendations") }
    var minLength by remember { mutableStateOf("30") }
    var maxLength by remember { mutableStateOf("30") }

    var seedTrackName by remember { mutableStateOf("") }
    var seedArtistName by remember { mutableStateOf("") }
    var seedArtistInput by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    var timePeriod by remember { mutableStateOf("overall") }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    val colors = MaterialTheme.colorScheme

    val textFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceContainerHigh,
        unfocusedContainerColor = colors.surfaceContainerHigh,
        disabledContainerColor = colors.surfaceContainerHigh,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )

    val smoothCornerShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 16.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusTR = 16.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = 16.dp,
            smoothnessAsPercentTL = 60,
            cornerRadiusBR = 16.dp,
            smoothnessAsPercentTR = 60
        )
    }

    val promptFieldShape = remember {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = 24.dp,
            smoothnessAsPercentBL = 60,
            cornerRadiusTR = 24.dp,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = 24.dp,
            smoothnessAsPercentTL = 60,
            cornerRadiusBR = 24.dp,
            smoothnessAsPercentTR = 60
        )
    }

    val infiniteTransition = rememberInfiniteTransition(label = "animation")
    val iconRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val iconScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    var isPressed by remember { mutableStateOf(false) }
    
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )
    
    val buttonCornerRadius by animateDpAsState(
        targetValue = if (isPressed || isGenerating) 24.dp else 50.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonCorner"
    )
    
    val buttonShape = remember(buttonCornerRadius) {
        AbsoluteSmoothCornerShape(
            cornerRadiusTL = buttonCornerRadius,
            smoothnessAsPercentBL = 60,
            cornerRadiusTR = buttonCornerRadius,
            smoothnessAsPercentBR = 60,
            cornerRadiusBL = buttonCornerRadius,
            smoothnessAsPercentTL = 60,
            cornerRadiusBR = buttonCornerRadius,
            smoothnessAsPercentTR = 60
        )
    }

    val isInputReady = when (selectedMode) {
        "similar-tracks" -> seedTrackName.isNotBlank() && seedArtistName.isNotBlank()
        "similar-artists" -> seedArtistInput.isNotBlank()
        "tag" -> tagInput.isNotBlank()
        else -> true
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .then(
                            if (isGenerating) Modifier
                                .rotate(iconRotation)
                                .scale(iconScale)
                            else Modifier
                        ),
                    shape = AbsoluteSmoothCornerShape(
                        cornerRadiusTL = 10.dp,
                        smoothnessAsPercentBL = 60,
                        cornerRadiusTR = 52.dp,
                        smoothnessAsPercentBR = 60,
                        cornerRadiusBL = 52.dp,
                        smoothnessAsPercentTL = 60,
                        cornerRadiusBR = 52.dp,
                        smoothnessAsPercentTR = 60
                    ),
                    color = if (isGenerating) colors.primaryContainer else colors.tertiaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_instant_mix_24),
                            contentDescription = "Mix Generator",
                            modifier = Modifier.size(32.dp),
                            tint = if (isGenerating) colors.onPrimaryContainer else colors.onTertiaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSuccess) "Perfectly Curated" else "Last.fm Recommendation Mix",
                        style = ExpTitleTypography.displayMedium.copy(
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (isSuccess) colors.tertiary else colors.primary
                    )
                    Text(
                        text = if (isSuccess) "Your sonic journey is ready" else "Based on Last.fm",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        color = if (isSuccess) colors.tertiary else colors.onSurfaceVariant
                    )
                }
            }

            // Description text
            Text(
                text = "Generate a tailored mix based on your Last.fm profile directly into your Daily Mix.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            // Number inputs
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = smoothCornerShape,
                color = colors.surfaceContainer,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Playlist size",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = minLength,
                            onValueChange = { minLength = it.filter { char -> char.isDigit() } },
                            label = { Text("Min songs") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = smoothCornerShape,
                            colors = textFieldColors,
                        )
                        OutlinedTextField(
                            value = maxLength,
                            onValueChange = { maxLength = it.filter { char -> char.isDigit() } },
                            label = { Text("Max songs") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = smoothCornerShape,
                            colors = textFieldColors,
                        )
                    }
                }
            }

            // Dropdown Selector for Mode
            var expandedModeDropdown by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = when (selectedMode) {
                        "recommendations" -> "My Recommendations"
                        "recent" -> "Recent Tracks"
                        "top" -> "Top Tracks"
                        "library" -> "My Library"
                        "similar-tracks" -> "Similar Tracks"
                        "similar-artists" -> "Similar Artists"
                        "tag" -> "By Tag / Genre"
                        "mix" -> "My Mix"
                        else -> "My Recommendations"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Generation Mode") },
                    trailingIcon = {
                        IconButton(onClick = { expandedModeDropdown = true }) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_keyboard_arrow_down_24),
                                contentDescription = "Select Mode"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { expandedModeDropdown = true })
                        },
                    shape = promptFieldShape,
                    colors = textFieldColors
                )
                
                DropdownMenu(
                    expanded = expandedModeDropdown,
                    onDismissRequest = { expandedModeDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    val modesList = listOf(
                        "recommendations" to "My Recommendations",
                        "recent" to "Recent Tracks",
                        "top" to "Top Tracks",
                        "library" to "My Library",
                        "similar-tracks" to "Similar Tracks",
                        "similar-artists" to "Similar Artists",
                        "tag" to "By Tag / Genre",
                        "mix" to "My Mix"
                    )
                    modesList.forEach { (modeId, modeName) ->
                        DropdownMenuItem(
                            text = { Text(modeName) },
                            onClick = {
                                selectedMode = modeId
                                expandedModeDropdown = false
                            }
                        )
                    }
                }
            }

            // Conditional inputs
            AnimatedVisibility(visible = selectedMode == "similar-tracks") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = seedTrackName,
                        onValueChange = { seedTrackName = it },
                        label = { Text("Seed Track Name") },
                        placeholder = { Text("e.g. Blinding Lights") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = smoothCornerShape,
                        colors = textFieldColors,
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = seedArtistName,
                        onValueChange = { seedArtistName = it },
                        label = { Text("Seed Artist Name") },
                        placeholder = { Text("e.g. The Weeknd") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = smoothCornerShape,
                        colors = textFieldColors,
                        singleLine = true
                    )
                }
            }

            AnimatedVisibility(visible = selectedMode == "similar-artists") {
                OutlinedTextField(
                    value = seedArtistInput,
                    onValueChange = { seedArtistInput = it },
                    label = { Text("Seed Artist Name") },
                    placeholder = { Text("e.g. Coldplay") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = smoothCornerShape,
                    colors = textFieldColors,
                    singleLine = true
                )
            }

            AnimatedVisibility(visible = selectedMode == "tag") {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("Genre or Tag") },
                    placeholder = { Text("e.g. rock, lofi, jazz") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = smoothCornerShape,
                    colors = textFieldColors,
                    singleLine = true
                )
            }

            AnimatedVisibility(visible = selectedMode == "top") {
                var expandedPeriodDropdown by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = when (timePeriod) {
                            "overall" -> "All Time"
                            "12month" -> "Yearly"
                            "6month" -> "6 Months"
                            "3month" -> "3 Months"
                            "1month" -> "Monthly"
                            "7day" -> "Weekly"
                            else -> "All Time"
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time Period") },
                        trailingIcon = {
                            IconButton(onClick = { expandedPeriodDropdown = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.rounded_keyboard_arrow_down_24),
                                    contentDescription = "Select Time Period"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { expandedPeriodDropdown = true })
                            },
                        shape = smoothCornerShape,
                        colors = textFieldColors
                    )
                    
                    DropdownMenu(
                        expanded = expandedPeriodDropdown,
                        onDismissRequest = { expandedPeriodDropdown = false }
                    ) {
                        val periodsList = listOf(
                            "overall" to "All Time",
                            "12month" to "Yearly",
                            "6month" to "6 Months",
                            "3month" to "3 Months",
                            "1month" to "Monthly",
                            "7day" to "Weekly"
                        )
                        periodsList.forEach { (pId, pName) ->
                            DropdownMenuItem(
                                text = { Text(pName) },
                                onClick = {
                                    timePeriod = pId
                                    expandedPeriodDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            // Error message with Retry
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut() + scaleOut(targetScale = 0.9f)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = smoothCornerShape,
                    color = colors.errorContainer,
                    onClick = onRetry
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = error ?: "",
                            color = colors.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.Restore, null, tint = colors.error, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Tap to Retry",
                                color = colors.error,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            
            // Success Feedback
            AnimatedVisibility(
                visible = isSuccess,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = smoothCornerShape,
                    color = colors.tertiaryContainer,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.rounded_instant_mix_24), 
                            contentDescription = null, 
                            tint = colors.onTertiaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = status ?: "Mix generated successfully!",
                            color = colors.onTertiaryContainer,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = GoogleSansRounded
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Generate Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(56.dp)
                    .scale(buttonScale)
                    .clip(buttonShape)
                    .background(
                        if (!isInputReady && !isGenerating) 
                            colors.surfaceContainerHighest 
                        else 
                            colors.primaryContainer
                    )
                    .pointerInput(isInputReady, isGenerating, isSuccess) {
                        detectTapGestures(
                            onPress = {
                                if (isInputReady && !isGenerating && !isSuccess) {
                                    isPressed = true
                                    tryAwaitRelease()
                                    isPressed = false
                                    val count = maxLength.toIntOrNull() ?: 30
                                    onGenerateClick(selectedMode, count, timePeriod, seedTrackName, seedArtistName, seedArtistInput, tagInput)
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSuccess) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = colors.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Ready to Play",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onTertiaryContainer
                        )
                    } else if (isGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = colors.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = status ?: "Generating…",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.rounded_instant_mix_24),
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                            tint = if (!isInputReady) 
                                colors.onSurfaceVariant 
                            else 
                                colors.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Generate Mix",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.SemiBold,
                            color = if (!isInputReady) 
                                colors.onSurfaceVariant 
                            else 
                                colors.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
