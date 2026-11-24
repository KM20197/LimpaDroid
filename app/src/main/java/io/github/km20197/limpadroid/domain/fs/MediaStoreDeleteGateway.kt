package io.github.km20197.limpadroid.domain.fs

import java.nio.file.Path

/**
 * Porta para consultar URI associado no MediaStore e executar a exclusão definitiva via ContentResolver.
 */
interface MediaStoreDeleteGateway {
  /**
   * Retorna o URI em string associado a [filePath] no MediaStore, ou `null` caso não exista registro.
   */
  suspend fun findAssociatedUri(filePath: Path): String?

  /**
   * Invoca a exclusão definitiva do arquivo no MediaStore via ContentResolver para o URI associado a [filePath].
   * Retorna `true` quando ao menos um registro foi removido via ContentResolver.
   */
  suspend fun deleteByAssociatedUri(filePath: Path): Boolean

  companion object {
    val NoOp: MediaStoreDeleteGateway = object : MediaStoreDeleteGateway {
      override suspend fun findAssociatedUri(filePath: Path): String? = null
      override suspend fun deleteByAssociatedUri(filePath: Path): Boolean = false
    }
  }
}
