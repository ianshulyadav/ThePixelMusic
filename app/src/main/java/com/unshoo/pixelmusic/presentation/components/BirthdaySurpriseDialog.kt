package com.unshoo.pixelmusic.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.github.BirthdaySurpriseService
import com.unshoo.pixelmusic.presentation.screens.DonateOptionsDialog
import com.unshoo.pixelmusic.presentation.viewmodel.BirthdaySurpriseViewModel
import com.unshoo.pixelmusic.presentation.viewmodel.WishSendState
import com.unshoo.pixelmusic.ui.theme.GoogleSansRounded
import kotlin.random.Random
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

private enum class BirthdayStep { Main, Wish, Gift, WishSent }

private val FestivePink = Color(0xFFFF7AA8)
private val FestiveGold = Color(0xFFFFC94D)
private val FestiveViolet = Color(0xFFB388FF)
private val FestiveMint = Color(0xFF6EE7C8)

private data class GiftOption(
    val icon: ImageVector,
    val titleRes: Int,
    val priceLabel: String,
    val amountInr: Int?,
)

private val giftOptions = listOf(
    GiftOption(Icons.Rounded.Coffee, R.string.birthday_gift_coffee, "₹99 · $3", 99),
    GiftOption(Icons.Rounded.Cake, R.string.birthday_gift_cake, "₹249 · $5", 249),
    GiftOption(Icons.Rounded.Celebration, R.string.birthday_gift_party, "₹499 · $10", 499),
    GiftOption(Icons.Rounded.Payments, R.string.birthday_gift_custom, "₹ · $", null),
)

/**
 * Celebratory December 14th popup for Anshul Yadav's birthday.
 * Lets users send a wish (shown on the PixelMusic website) or give a gift
 * through the same Ko-fi / UPI dialog used by the About → Donate section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdaySurpriseDialog(
    onDismiss: () -> Unit,
    viewModel: BirthdaySurpriseViewModel = hiltViewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val onOpenUrl: (String) -> Unit = { url -> openTrustedWebsiteUrl(context, url) }
    val config by viewModel.config.collectAsState()
    val siteConfig by viewModel.siteConfig.collectAsState()
    val dateLabel = remember(siteConfig.date) {
        java.time.LocalDate.of(2000, siteConfig.date.month, siteConfig.date.dayOfMonth)
            .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d", java.util.Locale.getDefault()))
    }
    val wishState by viewModel.wishState.collectAsState()
    var step by rememberSaveable { mutableStateOf(BirthdayStep.Main) }
    var selectedGift by remember { mutableStateOf<GiftOption?>(null) }

    LaunchedEffect(Unit) {
        viewModel.prepare()
        viewModel.resetWishState()
    }
    LaunchedEffect(wishState) {
        if (wishState == WishSendState.Sent) step = BirthdayStep.WishSent
    }

    selectedGift?.let { gift ->
        DonateOptionsDialog(
            onDismiss = { selectedGift = null },
            title = stringResource(R.string.birthday_gift_payment_title),
            upiNote = stringResource(R.string.birthday_gift_upi_note),
            upiAmountInr = gift.amountInr,
        )
    }

    val cardShape = AbsoluteSmoothCornerShape(32.dp, 60)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = 20.dp),
            shape = cardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Box {
                // Festive backdrop: soft gradient wash + falling confetti.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            ),
                        ),
                )
                // Confetti only on the celebration screens, not over the form or gift grid.
                if (step == BirthdayStep.Main || step == BirthdayStep.WishSent) {
                    ConfettiLayer(modifier = Modifier.matchParentSize())
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedContent(
                        targetState = step,
                        transitionSpec = {
                            (fadeIn(tween(260)) + scaleIn(tween(260), initialScale = 0.94f))
                                .togetherWith(fadeOut(tween(160)))
                        },
                        label = "birthdayStep",
                    ) { current ->
                        when (current) {
                            BirthdayStep.Main -> MainStep(
                                dateLabel = dateLabel,
                                onWish = { step = BirthdayStep.Wish },
                                onGift = { step = BirthdayStep.Gift },
                                onDismiss = onDismiss,
                            )
                            BirthdayStep.Wish -> WishStep(
                                wishState = wishState,
                                onBack = {
                                    viewModel.resetWishState()
                                    step = BirthdayStep.Main
                                },
                                onSend = viewModel::sendWish,
                            )
                            BirthdayStep.Gift -> GiftStep(
                                onBack = { step = BirthdayStep.Main },
                                onGiftSelected = { selectedGift = it },
                            )
                            BirthdayStep.WishSent -> WishSentStep(
                                onViewWishes = { onOpenUrl(config.wishesPageUrl) },
                                onGift = { step = BirthdayStep.Gift },
                                onDone = onDismiss,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainStep(
    dateLabel: String,
    onWish: () -> Unit,
    onGift: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CelebrationBadge()

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary,
        ) {
            Text(
                text = stringResource(R.string.birthday_date_chip, dateLabel),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        Text(
            text = stringResource(R.string.birthday_title),
            fontFamily = GoogleSansRounded,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Text(
            text = stringResource(R.string.birthday_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(2.dp))

        ChoiceCard(
            icon = Icons.Rounded.MailOutline,
            title = stringResource(R.string.birthday_send_wish),
            subtitle = stringResource(R.string.birthday_send_wish_desc),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            accent = MaterialTheme.colorScheme.primary,
            onClick = onWish,
        )
        ChoiceCard(
            icon = Icons.Rounded.CardGiftcard,
            title = stringResource(R.string.birthday_give_gift),
            subtitle = stringResource(R.string.birthday_give_gift_desc),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            accent = MaterialTheme.colorScheme.tertiary,
            onClick = onGift,
        )

        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.birthday_maybe_later))
        }
    }
}

@Composable
private fun WishStep(
    wishState: WishSendState,
    onBack: () -> Unit,
    onSend: (name: String, message: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    val sending = wishState == WishSendState.Sending
    val fieldShape = AbsoluteSmoothCornerShape(18.dp, 60)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepHeader(
            icon = Icons.Rounded.MailOutline,
            title = stringResource(R.string.birthday_send_wish),
            onBack = onBack,
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )

        Text(
            text = stringResource(R.string.birthday_wish_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(BirthdaySurpriseService.MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.birthday_wish_name_label)) },
            singleLine = true,
            enabled = !sending,
            shape = fieldShape,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = message,
            onValueChange = { message = it.take(BirthdaySurpriseService.MAX_MESSAGE_LENGTH) },
            label = { Text(stringResource(R.string.birthday_wish_message_label)) },
            placeholder = { Text(stringResource(R.string.birthday_wish_message_placeholder)) },
            enabled = !sending,
            shape = fieldShape,
            minLines = 3,
            maxLines = 6,
            supportingText = {
                Text(
                    text = "${message.length} / ${BirthdaySurpriseService.MAX_MESSAGE_LENGTH}",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                )
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )

        if (wishState is WishSendState.Error) {
            Text(
                text = stringResource(
                    when {
                        wishState.notConfigured -> R.string.birthday_wish_error_not_ready
                        wishState.reason == "not_kind" -> R.string.birthday_wish_error_not_kind
                        wishState.reason == "no_links" -> R.string.birthday_wish_error_no_links
                        wishState.reason == "slow_down" -> R.string.birthday_wish_error_slow_down
                        else -> R.string.birthday_wish_error_failed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = { onSend(name, message) },
            enabled = message.isNotBlank() && !sending,
            shape = AbsoluteSmoothCornerShape(18.dp, 60),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.birthday_wish_send_action), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GiftStep(
    onBack: () -> Unit,
    onGiftSelected: (GiftOption) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StepHeader(
            icon = Icons.Rounded.CardGiftcard,
            title = stringResource(R.string.birthday_give_gift),
            onBack = onBack,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
        )

        Text(
            text = stringResource(R.string.birthday_gift_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        giftOptions.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEachIndexed { index, gift ->
                    val useTertiary = (giftOptions.indexOf(gift) % 3 == 0)
                    GiftTile(
                        gift = gift,
                        container = if (useTertiary) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                        content = if (useTertiary) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = { onGiftSelected(gift) },
                        modifier = Modifier.weight(1f),
                    )
                    if (row.size == 1 && index == 0) Spacer(Modifier.weight(1f))
                }
            }
        }

        Text(
            text = stringResource(R.string.birthday_gift_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        )
    }
}

@Composable
private fun WishSentStep(
    onViewWishes: () -> Unit,
    onGift: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CelebrationBadge(icon = Icons.Rounded.Favorite)
        Text(
            text = stringResource(R.string.birthday_wish_sent_title),
            fontFamily = GoogleSansRounded,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.birthday_wish_sent_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onViewWishes,
            shape = AbsoluteSmoothCornerShape(18.dp, 60),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.birthday_view_wishes), fontWeight = FontWeight.Bold)
        }
        FilledTonalButton(
            onClick = onGift,
            shape = AbsoluteSmoothCornerShape(18.dp, 60),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.birthday_also_gift))
        }
        TextButton(onClick = onDone) { Text(stringResource(R.string.birthday_done)) }
    }
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CelebrationBadge(icon: ImageVector = Icons.Rounded.Cake) {
    val performanceMode = SmartImageCache.performanceModeEnabled
    val rotation = if (performanceMode) null else {
        rememberInfiniteTransition(label = "birthday badge").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
            label = "shape rotation",
        )
    }
    val outerShape = MaterialShapes.Cookie12Sided.toShape()
    val innerShape = MaterialShapes.Cookie9Sided.toShape()

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
        Box(
            modifier = Modifier
                .size(108.dp)
                .graphicsLayer { rotationZ = rotation?.value ?: 0f }
                .background(
                    Brush.sweepGradient(listOf(FestivePink, FestiveGold, FestiveViolet, FestivePink)),
                    outerShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(82.dp)
                .graphicsLayer { rotationZ = -(rotation?.value ?: 0f) }
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, innerShape),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = FestivePink,
            modifier = Modifier.size(48.dp),
        )
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    container: Color,
    content: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = AbsoluteSmoothCornerShape(24.dp, 60),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = AbsoluteSmoothCornerShape(16.dp, 60),
                color = accent,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.78f),
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = content.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun GiftTile(
    gift: GiftOption,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = AbsoluteSmoothCornerShape(22.dp, 60),
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = content.copy(alpha = 0.12f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(gift.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
                }
            }
            Text(
                text = stringResource(gift.titleRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = content,
            )
            Text(
                text = if (gift.amountInr == null) stringResource(R.string.birthday_gift_custom_desc) else gift.priceLabel,
                style = MaterialTheme.typography.labelMedium,
                color = content.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun StepHeader(
    icon: ImageVector,
    title: String,
    onBack: () -> Unit,
    container: Color,
    content: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.birthday_back))
        }
        Surface(shape = CircleShape, color = container, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            fontFamily = GoogleSansRounded,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private data class ConfettiPiece(
    val x: Float,
    val phase: Float,
    val speed: Float,
    val drift: Float,
    val size: Float,
    val spin: Float,
    val colorIndex: Int,
    val round: Boolean,
)

@Composable
private fun ConfettiLayer(modifier: Modifier = Modifier) {
    if (SmartImageCache.performanceModeEnabled) return
    val pieces = remember {
        val rnd = Random(1214)
        List(34) {
            ConfettiPiece(
                x = rnd.nextFloat(),
                phase = rnd.nextFloat(),
                speed = 0.6f + rnd.nextFloat() * 0.8f,
                drift = rnd.nextFloat() * 2f - 1f,
                size = 5f + rnd.nextFloat() * 6f,
                spin = 180f + rnd.nextFloat() * 540f,
                colorIndex = rnd.nextInt(5),
                round = rnd.nextFloat() < 0.35f,
            )
        }
    }
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        FestiveViolet,
        FestiveMint,
        FestiveGold,
        FestivePink,
    )
    val time by rememberInfiniteTransition(label = "confetti").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "confettiTime",
    )

    Canvas(modifier = modifier) {
        val density = this.density
        // Confetti only rains over the header (badge area) and fades out
        // before it reaches the title, so text always stays readable.
        val fallZone = minOf(size.height, 210.dp.toPx())
        pieces.forEach { p ->
            val progress = (p.phase + time * p.speed) % 1f
            val y = progress * (fallZone + 20f) - 20f
            val x = p.x * size.width + kotlin.math.sin((progress * 6.28f) + p.phase * 6.28f) * 18f * p.drift
            val alpha = (1f - progress * progress).coerceIn(0f, 1f) * 0.9f
            val color = palette[p.colorIndex].copy(alpha = alpha)
            val s = p.size * density
            rotate(degrees = progress * p.spin, pivot = Offset(x, y)) {
                if (p.round) {
                    drawCircle(color, radius = s / 2.4f, center = Offset(x, y))
                } else {
                    drawRect(color, topLeft = Offset(x - s / 2f, y - s / 4f), size = Size(s, s / 2f))
                }
            }
        }
    }
}

/**
 * The wishes page URL comes from a remote properties file, so only open
 * HTTPS links on PixelMusic's own hosts (never intent://, custom schemes, etc.).
 */
private val trustedWebsiteHosts = setOf("www.pixelmusic.me", "pixelmusic.me")

private fun openTrustedWebsiteUrl(context: android.content.Context, url: String) {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
    if (uri.scheme?.lowercase() != "https" || uri.host?.lowercase() !in trustedWebsiteHosts) {
        timber.log.Timber.w("Refusing to open untrusted birthday wishes URL: %s", url)
        return
    }
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: android.content.ActivityNotFoundException) {
        timber.log.Timber.w("No browser available for %s", url)
    }
}
