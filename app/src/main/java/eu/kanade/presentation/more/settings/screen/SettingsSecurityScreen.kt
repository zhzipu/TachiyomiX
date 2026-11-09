package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.ui.base.delegate.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.category.biometric.BiometricTimesScreen
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.LauncherDisguise
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsSecurityScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_security

    @Composable
    override fun getPreferences(): List<Preference> {
        val securityPreferences = remember { Injekt.get<SecurityPreferences>() }
        val privacyPreferences = remember { Injekt.get<PrivacyPreferences>() }
        return listOf(
            getSecurityGroup(securityPreferences),
            getFirebaseGroup(privacyPreferences),
        )
    }

    @Composable
    private fun getSecurityGroup(
        securityPreferences: SecurityPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val authSupported = remember { context.isAuthenticationSupported() }
        val useAuthPref = securityPreferences.useAuthenticator
        val useAuth by useAuthPref.collectAsState()

        val scope = rememberCoroutineScope()
        val isCbzPasswordSet by remember { CbzCrypto.isPasswordSetState(scope) }.collectAsState()
        val passwordProtectDownloads by securityPreferences.passwordProtectDownloads.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_security),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = useAuthPref,
                    title = stringResource(MR.strings.lock_with_biometrics),
                    enabled = authSupported,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_with_biometrics),
                        )
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.lockAppAfter,
                    entries = LockAfterValues
                        .associateWith {
                            when (it) {
                                -1 -> stringResource(MR.strings.lock_never)
                                0 -> stringResource(MR.strings.lock_always)
                                else -> pluralStringResource(MR.plurals.lock_after_mins, count = it, it)
                            }
                        },
                    title = stringResource(MR.strings.lock_when_idle),
                    enabled = authSupported && useAuth,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_when_idle),
                        )
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = securityPreferences.hideNotificationContent,
                    title = stringResource(MR.strings.hide_notification_content),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.secureScreen,
                    entries = SecurityPreferences.SecureScreenMode.entries
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.secure_screen),
                ),
                // SY -->
                Preference.PreferenceItem.SwitchPreference(
                    preference = securityPreferences.passwordProtectDownloads,
                    title = stringResource(SYMR.strings.password_protect_downloads),
                    subtitle = stringResource(SYMR.strings.password_protect_downloads_summary),
                    enabled = isCbzPasswordSet,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.encryptionType,
                    title = stringResource(SYMR.strings.encryption_type),
                    entries = SecurityPreferences.EncryptionType.entries
                        .associateWith { stringResource(it.titleRes) },
                    enabled = passwordProtectDownloads,

                ),
                kotlin.run {
                    var dialogOpen by remember { mutableStateOf(false) }
                    if (dialogOpen) {
                        PasswordDialog(
                            onDismissRequest = { dialogOpen = false },
                            onReturnPassword = { password ->
                                dialogOpen = false

                                CbzCrypto.deleteKeyCbz()
                                securityPreferences.cbzPassword.set(CbzCrypto.encryptCbz(password.replace("\n", "")))
                            },
                        )
                    }
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(SYMR.strings.set_cbz_zip_password),
                        onClick = {
                            dialogOpen = true
                        },
                    )
                },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(SYMR.strings.delete_cbz_archive_password),
                    onClick = {
                        CbzCrypto.deleteKeyCbz()
                        securityPreferences.cbzPassword.set("")
                    },
                    enabled = isCbzPasswordSet,
                ),
                kotlin.run {
                    val navigator = LocalNavigator.currentOrThrow
                    val count by securityPreferences.authenticatorTimeRanges.collectAsState()
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(SYMR.strings.action_edit_biometric_lock_times),
                        subtitle = pluralStringResource(
                            SYMR.plurals.num_lock_times,
                            count.size,
                            count.size,
                        ),
                        onClick = {
                            navigator.push(BiometricTimesScreen())
                        },
                        enabled = useAuth,
                    )
                },
                kotlin.run {
                    val selection by securityPreferences.authenticatorDays.collectAsState()
                    var dialogOpen by remember { mutableStateOf(false) }
                    if (dialogOpen) {
                        SetLockedDaysDialog(
                            onDismissRequest = { dialogOpen = false },
                            initialSelection = selection,
                            onDaysSelected = {
                                dialogOpen = false
                                securityPreferences.authenticatorDays.set(it)
                            },
                        )
                    }
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(SYMR.strings.biometric_lock_days),
                        subtitle = stringResource(SYMR.strings.biometric_lock_days_summary),
                        onClick = { dialogOpen = true },
                        enabled = useAuth,
                    )
                },
                // SY <--
                kotlin.run {
                    val currentSkin = LauncherDisguise.skinOf(securityPreferences.fakeLauncherIcon.get())
                    var dialogOpen by remember { mutableStateOf(false) }
                    if (dialogOpen) {
                        DisguiseDialog(
                            selectedAlias = currentSkin.alias,
                            onDismissRequest = { dialogOpen = false },
                            onSelect = { alias ->
                                dialogOpen = false
                                securityPreferences.fakeLauncherIcon.set(alias)
                                LauncherDisguise.apply(context, alias)
                                context.toast(MR.strings.disguise_app_icon_hint)
                            },
                        )
                    }
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.disguise_app_icon),
                        subtitle = disguiseSubtitle(currentSkin),
                        onClick = { dialogOpen = true },
                    )
                },
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.secure_screen_summary)),
            ),
        )
    }

    // SY -->
    private fun disguiseTypeNameRes(type: LauncherDisguise.Type?): StringResource = when (type) {
        LauncherDisguise.Type.Calculator -> MR.strings.fake_app_name_calc
        LauncherDisguise.Type.Notebook -> MR.strings.fake_app_name_note
        LauncherDisguise.Type.Study -> MR.strings.fake_app_name_study
        null -> MR.strings.app_name
    }

    @Composable
    private fun disguiseSubtitle(skin: LauncherDisguise.Skin): String {
        val name = stringResource(disguiseTypeNameRes(skin.type))
        return if (skin.type == null) {
            name
        } else {
            "$name · " + stringResource(MR.strings.disguise_icon_variant, skin.variant)
        }
    }

    /**
     * 伪装应用选择弹窗：默认入口一行，三种身份各一行图标（同类里的多套图标平铺成一行，当前生效项描边高亮）。
     */
    @Composable
    fun DisguiseDialog(
        selectedAlias: String,
        onDismissRequest: () -> Unit,
        onSelect: (String) -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(MR.strings.disguise_app_icon)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val defaultSkin = LauncherDisguise.skins.first()
                    DisguiseDefaultRow(
                        selected = defaultSkin.alias == selectedAlias,
                        onClick = { onSelect(defaultSkin.alias) },
                    )
                    LauncherDisguise.Type.entries.forEach { type ->
                        Text(
                            text = stringResource(disguiseTypeNameRes(type)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            LauncherDisguise.skinsOf(type).forEach { skin ->
                                DisguiseSkinTile(
                                    skin = skin,
                                    selected = skin.alias == selectedAlias,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelect(skin.alias) },
                                )
                            }
                        }
                    }
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = true),
            confirmButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    /** 默认入口（TachiyomiX 本体图标 + 应用名）。 */
    @Composable
    private fun DisguiseDefaultRow(
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val context = LocalContext.current
        // 自适应图标是 XML，painterResource 加载不了，直接问包管理器要 Drawable 再画成位图
        val appIcon = remember {
            runCatching { context.packageManager.getApplicationIcon(context.packageName) }.getOrNull()
        }
        val bitmap = remember(appIcon) {
            appIcon?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Icon(imageVector = Icons.Outlined.MenuBook, contentDescription = null)
            }
            Text(
                text = stringResource(MR.strings.app_name),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    /**
     * 素材按 88/108 排版、桌面遮罩只露 72/108，预览要放大 88/72 才是真实观感。
     */
    private const val PREVIEW_MASK_SCALE = 88f / 72f

    /** 单套图标：选中时外面一圈主色描边。 */
    @Composable
    private fun DisguiseSkinTile(
        skin: LauncherDisguise.Skin,
        selected: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit,
    ) {
        val previewRes = skin.previewRes ?: return
        val ringColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(
            modifier = modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(ringColor)
                .clickable(onClick = onClick)
                .padding(3.dp),
        ) {
            Image(
                painter = painterResource(previewRes),
                contentDescription = stringResource(MR.strings.disguise_icon_variant, skin.variant),
                contentScale = ContentScale.Crop,
                // 素材按 88/108 排版，桌面上会被遮罩裁到 72/108；
                // 预览也放大同样倍数再裁，看到的才是真实观感（否则显得四周留白偏大）
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(11.dp))
                    .graphicsLayer {
                        scaleX = PREVIEW_MASK_SCALE
                        scaleY = PREVIEW_MASK_SCALE
                    },
            )
        }
    }
    // SY <--

    // SY -->
    enum class DayOption(val day: Int, val stringRes: StringResource) {
        Sunday(SecureActivityDelegate.LOCK_SUNDAY, SYMR.strings.sunday),
        Monday(SecureActivityDelegate.LOCK_MONDAY, SYMR.strings.monday),
        Tuesday(SecureActivityDelegate.LOCK_TUESDAY, SYMR.strings.tuesday),
        Wednesday(SecureActivityDelegate.LOCK_WEDNESDAY, SYMR.strings.wednesday),
        Thursday(SecureActivityDelegate.LOCK_THURSDAY, SYMR.strings.thursday),
        Friday(SecureActivityDelegate.LOCK_FRIDAY, SYMR.strings.friday),
        Saturday(SecureActivityDelegate.LOCK_SATURDAY, SYMR.strings.saturday),
    }

    @Composable
    fun SetLockedDaysDialog(
        onDismissRequest: () -> Unit,
        initialSelection: Int,
        onDaysSelected: (Int) -> Unit,
    ) {
        val selected = remember(initialSelection) {
            DayOption.entries.filter { it.day and initialSelection == it.day }
                .toMutableStateList()
        }
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = { Text(text = stringResource(SYMR.strings.biometric_lock_days)) },
            text = {
                LazyColumn {
                    DayOption.entries.forEach { day ->
                        item {
                            val isSelected = selected.contains(day)
                            val onSelectionChanged = {
                                when (!isSelected) {
                                    true -> selected.add(day)
                                    false -> selected.remove(day)
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectionChanged() },
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onSelectionChanged() },
                                )
                                Text(
                                    text = stringResource(day.stringRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmButton = {
                TextButton(
                    onClick = {
                        onDaysSelected(
                            selected.fold(0) { i, day ->
                                i or day.day
                            },
                        )
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    @Composable
    fun PasswordDialog(
        onDismissRequest: () -> Unit,
        onReturnPassword: (String) -> Unit,
    ) {
        var password by rememberSaveable { mutableStateOf("") }
        var passwordVisibility by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissRequest,

            title = { Text(text = stringResource(SYMR.strings.cbz_archive_password)) },
            text = {
                TextField(
                    value = password,
                    onValueChange = { password = it },

                    maxLines = 1,
                    placeholder = { Text(text = stringResource(MR.strings.password)) },
                    label = { Text(text = stringResource(MR.strings.password)) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                passwordVisibility = !passwordVisibility
                            },
                        ) {
                            Icon(
                                imageVector = if (passwordVisibility) {
                                    Icons.Default.Visibility
                                } else {
                                    Icons.Default.VisibilityOff
                                },
                                contentDescription = null,
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onReturnPassword(password) },
                    ),
                    modifier = Modifier.onKeyEvent {
                        if (it.key == Key.Enter) {
                            return@onKeyEvent true
                        }
                        false
                    },
                    visualTransformation = if (passwordVisibility) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                )
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = true,
            ),
            confirmButton = {
                TextButton(
                    onClick = {
                        onReturnPassword(password)
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
    // SY <--

    @Composable
    private fun getFirebaseGroup(
        privacyPreferences: PrivacyPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_firebase),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = privacyPreferences.crashlytics,
                    title = stringResource(MR.strings.onboarding_permission_crashlytics),
                    subtitle = stringResource(MR.strings.onboarding_permission_crashlytics_description),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.firebase_summary)),
            ),
        )
    }
}

private val LockAfterValues = listOf(
    0, // Always
    1,
    2,
    5,
    10,
    -1, // Never
)
