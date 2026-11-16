package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTest {

  @Test
  fun `refreshPermissions updates both storage and notification flags`() {
    var storage = false
    var notifications = false
    val checker = object : PermissionChecker {
      override fun hasAllFilesAccess(): Boolean = storage
      override fun hasNotificationPermission(): Boolean = notifications
      override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
    }

    val vm = HomeViewModel(checker)
    assertFalse(vm.uiState.value.hasAllFilesAccess)
    assertFalse(vm.uiState.value.hasNotificationPermission)

    storage = true
    notifications = true
    vm.refreshPermissions()

    assertTrue(vm.uiState.value.hasAllFilesAccess)
    assertTrue(vm.uiState.value.hasNotificationPermission)
  }
}
