package me.ayuilos.miffan.ui.im

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Search01
import java.time.ZoneId
import kotlin.math.roundToInt

/** The pill shared by the Chats tab's search entry and the live field it turns into. */
@Composable
internal fun ImSearchPill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val row = @Composable {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(HugeIcons.Search01, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) Surface(onClick = onClick, shape = CircleShape, color = color, modifier = modifier) { row() }
    else Surface(shape = CircleShape, color = color, modifier = modifier) { row() }
}

/**
 * Search opened in place over the Chats tab: the pill rises from [fieldTop] (its root y in px) to the top
 * while the page behind fades out, and results fill the space below it.
 */
@Composable
internal fun ImSearchOverlay(transition: Transition<Boolean>, fieldTop: Float, onClose: () -> Unit, vm: ImSearchVM) {
    val progress by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow) },
        label = "imSearchProgress",
    ) { if (it) 1f else 0f }
    val density = LocalDensity.current
    val query by vm.query.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var overlayTop by remember { mutableFloatStateOf(0f) }
    var barHeight by remember { mutableIntStateOf(0) }
    var cancelWidth by remember { mutableIntStateOf(0) }
    val barTop = WindowInsets.statusBars.getTop(density) + with(density) { 4.dp.toPx() }

    // Focus only when search is opened, not when the page returns from a result with search still open.
    var focusPending by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (focusPending) {
            focusPending = false
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(transition.targetState) {
        if (!transition.targetState) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayTop = it.positionInRoot().y }
            .background(MaterialTheme.colorScheme.background.copy(alpha = progress))
            // Swallow touches so the chat list behind never reacts while search is up.
            .pointerInput(Unit) {}
    ) {
        ImSearchResults(
            vm = vm,
            query = query,
            onScroll = { keyboard?.hide() },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = with(density) { (barTop + barHeight).toDp() })
                .graphicsLayer {
                    alpha = progress
                    translationY = (1 - progress) * 24.dp.toPx()
                },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, lerp(fieldTop - overlayTop, barTop, progress).roundToInt()) }
                .onSizeChanged { barHeight = it.height }
        ) {
            val endPadding = with(density) { lerp(16.dp.toPx(), cancelWidth + 4.dp.toPx(), progress).toDp() }
            ImSearchPill(Modifier.padding(start = 16.dp, end = endPadding).padding(vertical = 4.dp)) {
                ImSearchInput(query, { vm.query.value = it }, focusRequester, onSearch = { keyboard?.hide() }, modifier = Modifier.weight(1f))
                if (query.isNotEmpty()) Icon(
                    HugeIcons.Cancel01,
                    contentDescription = stringResource(R.string.im_p5_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(interactionSource = null, indication = null) {
                            vm.query.value = ""
                            focusRequester.requestFocus()
                            keyboard?.show()
                        },
                )
            }
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .onSizeChanged { cancelWidth = it.width }
                    .graphicsLayer {
                        alpha = progress
                        translationX = (1 - progress) * size.width / 2
                    },
            ) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun ImSearchInput(query: String, onQuery: (String) -> Unit, focusRequester: FocusRequester, onSearch: () -> Unit, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.focusRequester(focusRequester),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) Text(stringResource(R.string.im_chats_search), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun ImSearchResults(vm: ImSearchVM, query: String, onScroll: () -> Unit, modifier: Modifier) {
    val nav = LocalNavController.current
    val results by vm.results.collectAsStateWithLifecycle()
    val ready = results.query == query.trim()
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) onScroll() }
    val bottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp)) {
        if (query.isNotBlank() && ready) {
            if (results.partners.isNotEmpty()) {
                item("partners") { Text(stringResource(R.string.im_tab_partners), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                items(results.partners, key = { "partner-${it.id}" }) { partner ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Screen.PartnerProfile(partner.id.toString())) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistantAvatar(partner.name, partner.avatar, Modifier.size(48.dp), onClick = { nav.navigate(Screen.PartnerProfile(partner.id.toString())) })
                        Text(imHighlight(partner.name, query.trim(), highlight), style = MaterialTheme.typography.titleMedium)
                    }
                    HorizontalDivider()
                }
            }
            if (results.messages.isNotEmpty()) {
                item("messages") { Text(stringResource(R.string.im_p5_search_records, results.messages.size), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                items(results.messages, key = { "message-${it.result.messageId}" }) { hit ->
                    val open = { nav.navigate(Screen.Thread(hit.partner.id.toString(), hit.result.messageId)) }
                    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistantAvatar(hit.partner.name, hit.partner.avatar, Modifier.size(48.dp), onClick = open)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(hit.partner.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(hit.result.updateAt.atZone(ZoneId.systemDefault()).toLocalDate().toLocalString(includeYear = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(imSnippet(hit.result.snippet, highlight), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
            if (results.failed) item("error") { ImEmpty(stringResource(R.string.im_p5_failed)) }
            else if (results.messages.isEmpty() && results.partners.isEmpty()) item("empty") { ImEmpty(stringResource(R.string.im_p5_search_empty)) }
        }
    }
}

private fun imHighlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var start = text.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(background = color), start, start + query.length)
            start = text.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}

/** The FTS API returns its own token boundaries, including segmented Chinese matches. */
private fun imSnippet(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Regex("\\[(.*?)]").findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(SpanStyle(background = color)) { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}
