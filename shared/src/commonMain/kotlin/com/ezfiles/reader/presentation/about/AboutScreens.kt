package com.ezfiles.reader.presentation.about


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.platform.LocalAppInfo
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.isOpenableLink
import com.ezfiles.reader.presentation.components.EzSegmented
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.components.EzSwitchRow
import com.ezfiles.reader.presentation.components.FileTypeTile
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.EzTopBar
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.PrimaryGradient
import com.ezfiles.reader.presentation.theme.ThemePreference
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.libraryBackground
import com.ezfiles.reader.presentation.theme.appSafeArea

/** Which About page is showing; Vision remembers whether it was opened from About, for Back. */
enum class AboutRoute { About, Vision, VisionFromAbout }

/** The About pages over the library; Back goes from Vision to About when it came from there. */
@Composable
fun AboutPages(
    route: AboutRoute,
    onRouteChange: (AboutRoute?) -> Unit,
    onSendFeedback: () -> Unit,
    theme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    /** Deletes the search index; null where there is none. */
    onClearSearchIndex: (() -> Unit)? = null,
    assistantEnabled: Boolean = false,
    onAssistantChange: (Boolean) -> Unit = {},
    contentEnabled: Boolean = false,
    onContentChange: (Boolean) -> Unit = {},
) {
    val back = { onRouteChange(if (route == AboutRoute.VisionFromAbout) AboutRoute.About else null) }
    PlatformBackHandler(enabled = true, onBack = back)
    when (route) {
        AboutRoute.About -> AboutScreen(
            onBack = back,
            onOpenVision = { onRouteChange(AboutRoute.VisionFromAbout) },
            onSendFeedback = onSendFeedback,
            theme = theme,
            onThemeChange = onThemeChange,
            haptics = haptics,
            onHapticsChange = onHapticsChange,
            onClearSearchIndex = onClearSearchIndex,
            assistantEnabled = assistantEnabled,
            onAssistantChange = onAssistantChange,
            contentEnabled = contentEnabled,
            onContentChange = onContentChange,
        )
        AboutRoute.Vision, AboutRoute.VisionFromAbout -> VisionScreen(onBack = back)
    }
}

/** Comfortable line length for reading on tablets and in landscape. */
private val ReadingWidth = 620.dp

/** A page with the glass top bar over the rose background, its content scrolling in a reading column. */
@Composable
private fun AboutFrame(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().libraryBackground().appSafeArea()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            EzTopBar(title = title, onBack = onBack)
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = ReadingWidth)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = EzSpacing.Gutter + 4.dp, vertical = 12.dp)
                        .padding(bottom = 24.dp),
                    content = content,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Why EzFiles

@Composable
fun VisionScreen(onBack: () -> Unit) {
    AboutFrame("Why EzFiles", onBack) {
        VisionText.forEach { block ->
            when (block) {
                is VisionBlock.Title -> VisionTitle(block.text)
                is VisionBlock.Paragraph -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp),
                    color = EzColors.Text,
                    modifier = Modifier.padding(vertical = 7.dp),
                )
                is VisionBlock.PullQuote -> PullQuote(block.text, Modifier.padding(vertical = 18.dp))
                is VisionBlock.Beliefs -> Beliefs(block.items, Modifier.padding(vertical = 10.dp))
                is VisionBlock.SignOff -> SignOff(block.text)
            }
        }
    }
}

@Composable
private fun VisionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 44.sp),
        color = EzColors.Text,
        modifier = Modifier.padding(top = 20.dp).semantics { heading() },
    )
    // The short red rule under a heading, as on the designed pages of the mockup.
    Box(Modifier.padding(top = 14.dp, bottom = 14.dp).size(width = 56.dp, height = 4.dp).background(EzColors.Primary, EzShapes.Pill))
}

/** A bold line of the text as a glass card with a red bar down its leading edge. */
@Composable
private fun PullQuote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .glass(EzShapes.Card, 0.6f),
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(EzColors.Primary))
        Text(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.ExtraBold),
            color = EzColors.Text,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 20.dp),
        )
    }
}

@Composable
private fun Beliefs(items: List<String>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().glass(EzShapes.Card, 0.45f).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items.forEach { CheckLine(it) }
    }
}

/** A line with a red check marker, for the beliefs and the privacy promises. */
@Composable
private fun CheckLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape).background(PrimaryGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AboutIcons.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
            color = EzColors.Text,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SignOff(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 32.dp, height = 3.dp).background(EzColors.Primary, EzShapes.Pill))
        Text(
            text,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp, fontStyle = FontStyle.Italic),
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// ---------------------------------------------------------------- About

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenVision: () -> Unit,
    onSendFeedback: () -> Unit,
    theme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    onClearSearchIndex: (() -> Unit)? = null,
    assistantEnabled: Boolean = false,
    onAssistantChange: (Boolean) -> Unit = {},
    contentEnabled: Boolean = false,
    onContentChange: (Boolean) -> Unit = {},
) {
    val appInfo = LocalAppInfo.current
    var showingLicences by rememberSaveable { mutableStateOf(false) }
    var showingFormats by rememberSaveable { mutableStateOf(false) }
    AboutFrame("About EzFiles", onBack) {
        AppHeader()
        Spacer(Modifier.height(20.dp))
        AppearanceCard(theme, onThemeChange, haptics, onHapticsChange, assistantEnabled, onAssistantChange, contentEnabled, onContentChange)
        Spacer(Modifier.height(12.dp))
        NavigationCard(
            icon = AboutIcons.Heart,
            title = "Why EzFiles",
            body = "The idea behind the app",
            onClick = onOpenVision,
        )
        Spacer(Modifier.height(12.dp))
        NavigationCard(
            icon = EzIcons.Document,
            title = "Supported files",
            body = "${SupportedFiles.size} kinds of file, from PDF to e-books",
            onClick = { showingFormats = true },
        )
        Spacer(Modifier.height(12.dp))
        PrivacyCard(onClearSearchIndex)
        CloudCrashReportingCard()
        SectionHeading("The team")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Team.forEach { TeamCard(it, canOpenLinks = appInfo.canHandOff, onOpenLink = { url -> appInfo.openLink(url) }) }
        }
        SectionHeading("More")
        if (appInfo.canHandOff) {
            NavigationCard(icon = AboutIcons.Mail, title = "Send feedback", body = "Write to us from your mail or messaging app", onClick = onSendFeedback)
            Spacer(Modifier.height(12.dp))
        }
        NavigationCard(icon = AboutIcons.Code, title = "Open-source licences", body = "The libraries and fonts EzFiles is built with", onClick = { showingLicences = true })
    }
    if (showingLicences) LicencesSheet(onDismiss = { showingLicences = false })
    if (showingFormats) SupportedFilesSheet(onDismiss = { showingFormats = false })
}

@Composable
private fun AppHeader() {
    val appInfo = LocalAppInfo.current
    val sizePx = with(LocalDensity.current) { 88.dp.roundToPx() }
    val icon = remember(sizePx) { appInfo.icon(sizePx) }
    Column(
        Modifier.fillMaxWidth().glass(EzShapes.Large, 0.55f).padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(88.dp).clip(EzShapes.Medium))
        } else {
            Box(Modifier.size(88.dp).clip(EzShapes.Medium).background(PrimaryGradient), contentAlignment = Alignment.Center) {
                Icon(EzIcons.Document, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp))
            }
        }
        Text(
            "EzFiles",
            style = MaterialTheme.typography.headlineMedium,
            color = EzColors.Text,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Text(
            "Version ${appInfo.versionName} (${appInfo.versionCode})",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            TAGLINE,
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = EzColors.Accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = EzColors.Text,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp, start = 4.dp).semantics { heading() },
    )
}

/** A glass card that opens something: icon badge, title and line, chevron. */
@Composable
private fun NavigationCard(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(EzShapes.Card, 0.55f)
            .clickable(role = Role.Button, onClick = rememberHapticActivation(onClick))
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
        }
        Icon(EzIcons.ChevronRight, null, tint = EzColors.TextSecondary, modifier = Modifier.size(EzSpacing.BarIcon))
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(EzColors.Selected), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = EzColors.Primary, modifier = Modifier.size(22.dp))
    }
}

private val PrivacyPromises = listOf(
    "Document reading and processing work offline. Optional cloud crash diagnostics require your consent.",
    "No ads, no accounts and no usage analytics.",
    "Document processing stays on your device. Sharing, printing and optional assistants disclose only what you choose.",
    "Local crash reports are yours to share. Optional Firebase diagnostics are off by default and have a separate consent control.",
)

/** The app's theme: System, Light or Dark. */
@Composable
private fun AppearanceCard(
    theme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
    haptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    assistantEnabled: Boolean,
    onAssistantChange: (Boolean) -> Unit,
    contentEnabled: Boolean,
    onContentChange: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
        EzSegmented(
            options = ThemePreference.entries,
            selected = theme,
            label = { it.label },
            onSelect = onThemeChange,
            modifier = Modifier.fillMaxWidth(),
        )
        EzSwitchRow(
            title = "Touch feedback",
            body = "A light tap when you choose, switch or finish something",
            checked = haptics,
            onCheckedChange = onHapticsChange,
        )
        EzSwitchRow(
            title = "Fily and assistants",
            body = "Lets Fily, the voice assistant on this phone, and assistants built into Android find your files and open, share or print them when you ask. Off turns Fily off and no assistant can see your files.",
            checked = assistantEnabled,
            onCheckedChange = onAssistantChange,
        )
        if (assistantEnabled) {
            EzSwitchRow(
                title = "Let them read inside files",
                body = "Needed to answer questions about a document, like the total on a bill. Off: only file names, sizes and dates. Nothing leaves your phone.",
                checked = contentEnabled,
                onCheckedChange = onContentChange,
            )
            com.ezfiles.reader.presentation.assistant.ModelInstallRow(com.ezfiles.reader.application.assistant.AssistantModel.Fily)
        }
    }
}

@Composable
private fun PrivacyCard(onClearSearchIndex: (() -> Unit)?) {
    var cleared by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(AboutIcons.Shield)
            Text("Privacy", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, modifier = Modifier.semantics { heading() })
        }
        PrivacyPromises.forEach { CheckLine(it) }
        if (onClearSearchIndex != null) {
            CheckLine("Search reads your files only on this device. Its index stays in EzFiles' private storage and skips locked files.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (cleared) "Search index cleared" else "Search index",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (!cleared) {
                    EzTextButton("Clear", {
                        onClearSearchIndex()
                        cleared = true
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeamCard(member: TeamMember, canOpenLinks: Boolean, onOpenLink: (String) -> Unit) {
    val links = if (canOpenLinks) member.links.filter { isOpenableLink(it.url) } else emptyList()
    Column(Modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(PrimaryGradient).clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initialsOf(member.name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
                if (member.role.isNotBlank()) Text(member.role, style = MaterialTheme.typography.bodyMedium, color = EzColors.Accent)
            }
        }
        if (member.bio.isNotBlank()) {
            Text(
                member.bio,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = EzColors.Text,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (links.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                links.forEach { link -> EzTextButton(link.label, { onOpenLink(link.url) }) }
            }
        }
    }
}

/** Every kind of file EzFiles opens: badge, name, extensions and what it does with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SupportedFilesSheet(onDismiss: () -> Unit) {
    EzSheet(onDismissRequest = onDismiss, title = "Supported files") {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Everything opens on this phone, without the internet. Files show the way they were made.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            items(SupportedFiles, key = { it.title }) { group ->
                Row(
                    Modifier.fillMaxWidth().glass(EzShapes.Card, 0.45f).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FileTypeTile(group.badge)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(group.title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                        Text(group.summary, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                        Text(
                            group.extensions,
                            style = MaterialTheme.typography.labelMedium,
                            color = EzColors.Accent,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicencesSheet(onDismiss: () -> Unit) {
    EzSheet(onDismissRequest = onDismiss, title = "Open-source licences") {
        LazyColumn(
            Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "EzFiles is built with these components. Thank you to everyone who made them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                )
            }
            items(Licences) { licence ->
                Column {
                    Text(licence.name, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
                    Text(licence.licence, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                }
            }
        }
    }
}
