package io.github.km20197.limpadroid.data.fs

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

sealed interface StorageRootResolution {
  data class Available(
    val rootPath: Path,
    val volumeDescription: String,
  ) : StorageRootResolution

  data class Unavailable(
    val reason: String,
    val volumeDescription: String? = null,
  ) : StorageRootResolution
}

interface StorageVolumeResolver {
  fun resolvePrimarySharedStorage(): StorageRootResolution
}

class AndroidStorageVolumeResolver(
  private val context: Context,
) : StorageVolumeResolver {

  override fun resolvePrimarySharedStorage(): StorageRootResolution {
    val storageManager = context.getSystemService(StorageManager::class.java)
      ?: return StorageRootResolution.Unavailable("StorageManager indisponível")

    val primaryVolume = runCatching { storageManager.primaryStorageVolume }
      .getOrElse { error ->
        return StorageRootResolution.Unavailable(
          reason = "Volume primário indisponível (${error.javaClass.simpleName})",
        )
      }
    val description = runCatching { primaryVolume.getDescription(context) }.getOrNull()
    val state = primaryVolume.state
    if (state != Environment.MEDIA_MOUNTED) {
      return StorageRootResolution.Unavailable(
        reason = "Volume primário não montado ($state)",
        volumeDescription = description,
      )
    }

    val directory = primaryVolume.directory
      ?: return StorageRootResolution.Unavailable(
        reason = "Diretório do volume primário indisponível",
        volumeDescription = description,
      )

    val path = directory.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    ) {
      return StorageRootResolution.Unavailable(
        reason = "Diretório raiz inacessível ou inválido: $path",
        volumeDescription = description,
      )
    }

    return StorageRootResolution.Available(
      rootPath = path,
      volumeDescription = description ?: "Armazenamento interno compartilhado",
    )
  }
}
