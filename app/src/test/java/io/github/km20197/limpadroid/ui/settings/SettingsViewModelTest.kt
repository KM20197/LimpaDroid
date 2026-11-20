package io.github.km20197.limpadroid.ui.settings

import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

  private val mainDispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `toggling superfluous rules and managing exclusions updates UI state`() {
    val prefs = FakePreferences()
    val vm = SettingsViewModel(prefs)

    assertEquals(SuperfluousRuleId.DEFAULT_ENABLED_IDS, vm.uiState.value.enabledSuperfluousRules)
    assertTrue(vm.uiState.value.userExcludedPaths.isEmpty())

    vm.setSuperfluousRuleEnabled(SuperfluousRuleId.OLD_APK.ruleId, false)
    assertFalse(SuperfluousRuleId.OLD_APK.ruleId in vm.uiState.value.enabledSuperfluousRules)

    vm.setSuperfluousRuleEnabled(SuperfluousRuleId.OLD_APK.ruleId, true)
    assertTrue(SuperfluousRuleId.OLD_APK.ruleId in vm.uiState.value.enabledSuperfluousRules)

    vm.showAddExclusionDialog()
    assertTrue(vm.uiState.value.isAddExclusionVisible)
    vm.updateNewExclusionInput("  /storage/emulated/0/Documents/Privado  ")
    vm.confirmAddExclusion()

    assertFalse(vm.uiState.value.isAddExclusionVisible)
    assertEquals(listOf("/storage/emulated/0/Documents/Privado"), vm.uiState.value.userExcludedPaths)

    vm.removeUserExclusion("/storage/emulated/0/Documents/Privado")
    assertTrue(vm.uiState.value.userExcludedPaths.isEmpty())
  }

  @Test
  fun `blank exclusion input closes dialog without persisting`() {
    val prefs = FakePreferences()
    val vm = SettingsViewModel(prefs)

    vm.showAddExclusionDialog()
    vm.updateNewExclusionInput("   ")
    vm.confirmAddExclusion()

    assertFalse(vm.uiState.value.isAddExclusionVisible)
    assertTrue(vm.uiState.value.userExcludedPaths.isEmpty())
  }

  @Test
  fun `duplicate min size and priority folder order can be configured reordered removed and reset`() {
    val prefs = FakePreferences()
    val vm = SettingsViewModel(prefs)

    assertEquals(DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES, vm.uiState.value.duplicateMinSizeBytes)
    assertEquals(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS, vm.uiState.value.duplicatePriorityFolders)

    vm.setDuplicateMinSizeBytes(65_536L)
    assertEquals(65_536L, vm.uiState.value.duplicateMinSizeBytes)

    vm.showAddPriorityFolderDialog()
    assertTrue(vm.uiState.value.isAddPriorityFolderVisible)
    vm.updateNewPriorityFolderInput("  /Download/Arquivos/  ")
    vm.confirmAddPriorityFolder()
    assertFalse(vm.uiState.value.isAddPriorityFolderVisible)
    assertEquals("Download/Arquivos", vm.uiState.value.duplicatePriorityFolders.last())

    // Move "DCIM" (índice 1) para índice 0
    vm.movePriorityFolderUp(1)
    assertEquals("DCIM", vm.uiState.value.duplicatePriorityFolders[0])
    assertEquals("DCIM/Camera", vm.uiState.value.duplicatePriorityFolders[1])

    // Move índice 0 de volta para baixo
    vm.movePriorityFolderDown(0)
    assertEquals("DCIM/Camera", vm.uiState.value.duplicatePriorityFolders[0])

    // Remove última entrada
    val lastIdx = vm.uiState.value.duplicatePriorityFolders.lastIndex
    vm.removePriorityFolder(lastIdx)
    assertFalse("Download/Arquivos" in vm.uiState.value.duplicatePriorityFolders)

    // Restaura padrão
    vm.resetPriorityFolders()
    assertEquals(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS, vm.uiState.value.duplicatePriorityFolders)
  }

  @Test
  fun `whatsapp age bounds media types and auto preselect update reactive UI state`() {
    val prefs = FakePreferences()
    val vm = SettingsViewModel(prefs)

    assertEquals(WhatsAppMediaRules.DEFAULT_AGE_DAYS, vm.uiState.value.whatsappMinAgeDays)
    assertEquals(WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES, vm.uiState.value.whatsappEnabledMediaTypes)
    assertFalse(vm.uiState.value.whatsappAutoPreselect)

    vm.setWhatsAppMinAgeDays(2)
    assertEquals(WhatsAppMediaRules.MIN_AGE_DAYS, vm.uiState.value.whatsappMinAgeDays)

    vm.setWhatsAppMinAgeDays(999)
    assertEquals(WhatsAppMediaRules.MAX_AGE_DAYS, vm.uiState.value.whatsappMinAgeDays)

    vm.setWhatsAppMinAgeDays(30)
    assertEquals(30, vm.uiState.value.whatsappMinAgeDays)

    vm.setWhatsAppMediaTypeEnabled(WhatsAppMediaType.ANIMATED_GIFS, true)
    assertTrue(WhatsAppMediaType.ANIMATED_GIFS.name in vm.uiState.value.whatsappEnabledMediaTypes)

    vm.setWhatsAppMediaTypeEnabled(WhatsAppMediaType.VOICE_NOTES, false)
    assertFalse(WhatsAppMediaType.VOICE_NOTES.name in vm.uiState.value.whatsappEnabledMediaTypes)

    vm.setWhatsAppAutoPreselect(true)
    assertTrue(vm.uiState.value.whatsappAutoPreselect)
  }

  private class FakePreferences : AppPreferencesRepository {
    private val notif = MutableStateFlow(true)
    private val rules = MutableStateFlow(SuperfluousRuleId.DEFAULT_ENABLED_IDS)
    private val exclusions = MutableStateFlow<Set<String>>(emptySet())
    private val dupMinSize = MutableStateFlow(DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES)
    private val dupPriorityFolders = MutableStateFlow(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS)
    private val waMinAgeDays = MutableStateFlow(WhatsAppMediaRules.DEFAULT_AGE_DAYS)
    private val waMediaTypes = MutableStateFlow(WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES)
    private val waAutoPreselect = MutableStateFlow(false)

    override val notificationPromptHandled: Flow<Boolean> = notif
    override val enabledSuperfluousRuleIds: Flow<Set<String>> = rules
    override val userExcludedPaths: Flow<Set<String>> = exclusions
    override val duplicateMinSizeBytes: Flow<Long> = dupMinSize
    override val duplicatePriorityFolders: Flow<List<String>> = dupPriorityFolders
    override val whatsappMinAgeDays: Flow<Int> = waMinAgeDays
    override val whatsappEnabledMediaTypes: Flow<Set<String>> = waMediaTypes
    override val whatsappAutoPreselect: Flow<Boolean> = waAutoPreselect

    override suspend fun setNotificationPromptHandled(handled: Boolean) {
      notif.value = handled
    }

    override suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) {
      val next = rules.value.toMutableSet()
      if (enabled) next.add(ruleId) else next.remove(ruleId)
      rules.value = next
    }

    override suspend fun addUserExcludedPath(path: String) {
      val trimmed = path.trim()
      if (trimmed.isNotEmpty()) exclusions.value = exclusions.value + trimmed
    }

    override suspend fun removeUserExcludedPath(path: String) {
      exclusions.value = exclusions.value - path
    }

    override suspend fun setDuplicateMinSizeBytes(bytes: Long) {
      dupMinSize.value = bytes.coerceAtLeast(1L)
    }

    override suspend fun setDuplicatePriorityFolders(folders: List<String>) {
      dupPriorityFolders.value = folders
        .map { DuplicateKeepPolicy.normalizeFolderSegments(it).joinToString("/") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    }

    override suspend fun setWhatsAppMinAgeDays(days: Int) {
      waMinAgeDays.value = WhatsAppMediaRules.clampAgeDays(days)
    }

    override suspend fun setWhatsAppMediaTypeEnabled(typeName: String, enabled: Boolean) {
      val next = waMediaTypes.value.toMutableSet()
      if (enabled) next.add(typeName) else next.remove(typeName)
      waMediaTypes.value = next
    }

    override suspend fun setWhatsAppAutoPreselect(enabled: Boolean) {
      waAutoPreselect.value = enabled
    }
  }
}
