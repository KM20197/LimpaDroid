# Registro de Auditorias Independentes (Seção 14)

Este documento consolida as avaliações independentes executadas ao final de cada fase conforme a Seção 14 do Plano de Desenvolvimento (critério de aceite mínimo: nota final `>= 9.0`).

---

## Fase 0 — Base e Infraestrutura
- **Nota inicial:** 6,5 / 10
- **Principais problemas apontados:**
  1. Falha de instalação da plataforma Android 37 no CI (`grep -E '^platforms;android-37$'` vs `platforms;android-37.0`) e ausência de `debug.keystore.base64` no repositório.
  2. Divergência entre `minSdk = 37` e compatibilidade de execução no Robolectric sob JDK 17 (`UnsupportedOperationException` em `DefaultSdkProvider`).
  3. Presença inicial da permissão `QUERY_ALL_PACKAGES` sem recurso consumidor ativo.
  4. Resíduos do ambiente template (`.env.example`, fontes estáticas com pesos declarados sem suporte no arquivo `.ttf`, ícones redundantes em pastas de densidade, falta de ordenação antes do truncamento no Modo Diagnóstico e tratamento de `DirectoryIteratorException`).
- **Correções realizadas:**
  - Fixado `minSdk = 30`, bytecode target Java/Kotlin `17` e execução de CI em JDK `21` com `--add-opens` para Robolectric `4.18` e SDK `platforms;android-37.0` (`build-tools;36.0.0`), geração efêmera de `debug.keystore` no CI, `permissions: contents: read` e `concurrency` com cancelamento automático.
  - Removida `QUERY_ALL_PACKAGES` até a Fase 4; removidos `.env.example` e PNGs de densidade redundantes (`mipmap-*`), mantendo apenas `mipmap-anydpi`.
  - Substituída `Space Grotesk Light` por `FontFamily.SansSerif` para títulos, ajustados pesos de `plus_jakarta_sans.ttf` e `jetbrains_mono.ttf` para `FontWeight.Normal` e adicionada licença `licenses/OFL.txt`.
  - Aplicados ordenação prévia ao truncamento no `DirectoryInspector`, marcador explícito `(lista truncada)`, captura de `DirectoryIteratorException` e controle `refreshJob` contra condições de corrida no `DiagnosticViewModel`.
- **Nota final:** 9,3 / 10

---

## Fase 1 — Infraestrutura de Varredura
- **Nota inicial:** 8,2 / 10
- **Principais problemas apontados:**
  1. Erro de Lint `UnusedBoxWithConstraintsScope` em `ScanStatusScreen.kt`.
  2. Ajuste de sincronização assíncrona em `LimpaDroidRobolectricTest` após escrita no Room.
- **Correções realizadas:**
  - Substituído `BoxWithConstraints` por `Box` em `ScanStatusScreen.kt` e adicionada espera determinística (`waitUntil`) no teste de integração Robolectric.
  - Validados `PathGuard`, `FileWalker` iterativo sem seguir links simbólicos, `Hasher` SHA-256 (1 MiB / partial 64 KiB), checkpoint por subárvores no Room (`scan_sessions`, `scan_candidates`, `scan_errors`, `hash_cache`), `LocalScanLogger` rotativo e `ScanWorker` em primeiro plano (`dataSync`) com 41 testes aprovados e 0 erros de Lint.
- **Nota final:** 9,4 / 10

---

## Fase 2 — Núcleo da Lixeira
- **Entregas avaliadas:** `TrashStorage` (`Documents/LimpaDroid/.lixeira`, `.nomedia`, `metadata.json`, `ATOMIC_MOVE` + fallback cópia/SHA-256, preservação de `mtime`, sufixo `(restaurado)`), `TrashRepository` (diário `PENDING -> IN_TRASH`, reconciliação pós-falha, reconstrução de índice após reinstalação, `PathGuard`, revalidação pré-movimentação), `PurgeWorker` (retenção 30 dias via `WorkManager`), `AndroidMediaScannerHook`, Room `v2` com `MIGRATION_1_2`, `TrashViewModel`, `HistoryViewModel`, telas `TrashScreen` e `HistoryScreen`.
- **Nota inicial:** 8,3 / 10
- **Principais problemas apontados na auditoria:**
  1. Uso de `Files.writeString` e `Files.readString` em `TrashStorage.kt` incompatível com `minSdk = 30` (erro de Lint `NewApi` exigindo API 36.1+).
  2. Ausência de tratamento explícito de `DirectoryIteratorException` em `TrashStorage.scanRecoverableEntries` e ausência de `Locale.getDefault()` explícito em `String.format` no feedback da `TrashScreen`.
  3. Falha na compilação inicial de testes unitários com `MediaScannerHook` (`interface` sem `fun`) e ajuste necessário no escalonamento de corrotinas (`runCurrent()`) em `DiagnosticViewModelTest` e na unidade esperada (`"1,0 KiB"`) em `TrashViewModelTest`.
  4. Recurso não utilizado `home_trash_desc` em `strings.xml` gerando aviso de Lint `UnusedResources`.
- **Correções realizadas:**
  - Substituídos `Files.writeString`/`Files.readString` por `Files.write`/`Files.readAllBytes` com `Charsets.UTF_8` (compatíveis com API 26+).
  - Envolvida a iteração de `DirectoryStream` em `TrashStorage.scanRecoverableEntries` com tratamento de `DirectoryIteratorException` e `IOException`; adicionado `Locale.getDefault()` em `TrashScreen.kt`.
  - Convertida `MediaScannerHook` em `fun interface`; corrigidos `DiagnosticViewModelTest` (`runCurrent()`) e `TrashViewModelTest`; adicionado teste de migração Room `MIGRATION_1_2` em schema v1 completo; removido recurso não usado `home_trash_desc`.
- **Verificação final objetiva:** `./gradlew lint testDebugUnitTest assembleDebug` concluído com sucesso (`53` testes em `17` suítes: `0` falhas, `0` erros, `0` ignorados; `0` erros de Lint; APK debug gerado).
- **Observações residuais para as fases seguintes (sem bloqueio da Fase 2):** exibir nomes localizados de categoria na tela de histórico na Fase 6 e registrar explicitamente no log eventual falha individual dentro do laço de `emptyTrash()`/`purgeExpired()`.
- **Nota final:** 9,3 / 10

---

## Fase 3 — Dados Supérfluos (Sem Aplicativo Dono)
- **Entregas avaliadas:** 7 regras isoladas da Seção 7.2 em `SuperfluousRules.kt` (`THUMBS`, `PARTIAL_DL`, `LOST_DIR`, `EMPTY_DIR`, `ZERO_BYTE`, `OLD_APK`, `STALE_MEDIASTORE`) com exemplos positivos e negativos; `SuperfluousScanEngine` com cancelamento cooperativo, controle por regras ativas e consulta ao `MediaStoreGateway` (`AndroidMediaStoreGateway`); suporte a seleção/limpeza em lote e reparo direto de `STALE_MEDIASTORE` (`TrashRepository.repairStaleMediaStore`) sem passagem pela lixeira; migração dos rótulos localizados no Histórico; `CategoryResultsScreen` / `CategoryResultsViewModel` (agrupamento por regra, miniaturas locais, rodapé fixo e diálogo de confirmação); `SettingsScreen` / `SettingsViewModel` (switches de regras e exceções do usuário em `DataStore`); métricas ao vivo e atalhos na `HomeScreen`.
- **Nota inicial:** 7,9 / 10
- **Principais problemas apontados na auditoria:**
  1. `EmptyDirRule` protegia apenas `Android` e `Android/media` exatos, gerando falso positivo em subpastas vazias legítimas dentro de `Android/media/<pacote>` (como `Android/media/com.whatsapp/WhatsApp/Media`) na árvore de controle.
  2. `DefaultPathGuard` resolvia `userExcludedPaths` relativas usando o diretório de trabalho JVM em vez da raiz do armazenamento compartilhado (`storageRoot`).
  3. Avisos de Lint `ModifierParameter` em `HomeScreen.kt` (`HomeScreen` e `CategoryOverviewCard`) e ícone obsoleto `Icons.Filled.InsertDriveFile` em `CategoryResultsScreen.kt`.
  4. Recursos `strings.xml` da Fase 3 e atualização completa dos fakes/testes (regras, motor, ViewModels, MediaStore repair e fluxo Robolectric completo).
- **Correções realizadas:**
  - Protegida toda a subárvore `Android/*` em `EmptyDirRule`, validando `0` falsos positivos em árvore limpa de controle (`SuperfluousRulesTest`).
  - Atualizado `DefaultPathGuard` para resolver exceções relativas do usuário contra `storageRoot`, mantendo rejeição estrita de `..`, links simbólicos e caminhos fora da raiz, com cobertura em `SuperfluousScanEngineTest`.
  - Corrigida ordem do parâmetro `modifier` em `HomeScreen.kt` (zerando avisos `ModifierParameter` no Lint) e atualizado ícone para `Icons.AutoMirrored.Filled.InsertDriveFile` (zerando avisos do compilador Kotlin).
  - Adicionados os recursos pt-BR em `strings.xml` e validados 18 novos casos de teste cobrindo todas as regras, árvore de controle, motor completo, ViewModels de Resultados/Configurações/Início, `repairStaleMediaStore` e jornada Compose/Room em Robolectric.
- **Verificação final objetiva:** `gradle lint testDebugUnitTest assembleDebug` concluído com sucesso (`71` testes em `21` suítes: `0` falhas, `0` erros, `0` ignorados; `0` erros de Lint; `0` avisos do compilador Kotlin; APK debug gerado).
- **Observações residuais para fases seguintes (sem bloqueio da Fase 3):** validar `AndroidMediaStoreGateway` e miniaturas de mídia em dispositivo físico conforme `docs/ROTEIRO_TESTE.md`, e expandir `SettingsScreen` com parâmetros de WhatsApp, órfãos e duplicados nas Fases 4 a 6.
- **Nota final:** 9,4 / 10

---

## Fase 4 — Possíveis Dados Remanescentes de Aplicativos Removidos
- **Entregas avaliadas:** declaração justificada de `QUERY_ALL_PACKAGES` no `AndroidManifest.xml` (`DECISOES.md` §12.6); tabela Room `known_apps` (`KnownAppEntity`, `KnownAppDao`) e migração `MIGRATION_2_3` (`LimpaDroidDatabase` v3); `InstalledAppsRepository` (`AppIdentifierNormalizer`) e `AndroidInstalledAppsRepository` consultando `MATCH_UNINSTALLED_PACKAGES` e `MATCH_ARCHIVED_PACKAGES` (API 35+, mais `isArchived`), sincronizando com `Mutex` e limpando `removedAt` em reinstalações; `OrphanDirectoryClassifier` (`ORPHAN_MEDIA_PKG` HIGH, `ORPHAN_ROOT_KNOWN` MEDIUM, `ORPHAN_ROOT_PATTERN` LOW) com blindagem de pastas padrão do sistema, `Documents/LimpaDroid` e pastas do WhatsApp; `OrphanScanEngine` com cálculo recursivo de tamanho sem seguir links simbólicos, captura de `DirectoryIteratorException` e cancelamento cooperativo; extensão de `PathGuard.hasProtectedDescendant` e proteção no `TrashRepository.moveToTrash` para diretórios com subcaminhos protegidos; integração na `HomeScreen`/`HomeViewModel`, `ScanWorker` (`ScanScope.ORPHAN`) e `CategoryResultsScreen` exibindo nome do aplicativo removido e data de remoção.
- **Nota inicial:** 8,1 / 10
- **Principais problemas apontados na auditoria:**
  1. Uso do construtor obsoleto `Locale("pt", "BR")` em `OrphanRules.kt` para formatação de datas.
  2. Ausência de verificação explícita `config.pathGuard.isTraversalAllowed(...)` antes de listar `Android/media` e cada diretório pai (`Documents`, `Pictures`, `Movies`, `Music`, `Download`) em `OrphanScanEngine.kt`.
  3. O teste legado `MIGRATION_1_2` em `TrashRepositoryAndPurgeWorkerRobolectricTest.kt` registrava apenas `MIGRATION_1_2` sem `MIGRATION_2_3` após a elevação do banco para `version = 3`.
  4. Falta de caso de teste dedicado em `OrphanScanEngineTest.kt` validando exclusões de usuário aplicadas diretamente às pastas-pai (`Android/media` e `Documents`).
- **Correções realizadas:**
  - Substituído `Locale("pt", "BR")` por `Locale.forLanguageTag("pt-BR")` em `OrphanRules.kt`.
  - Adicionada guarda `config.pathGuard.isTraversalAllowed(...)` antes da enumeração de `androidMediaDir` e dos diretórios pais conhecidos em `OrphanScanEngine.kt`.
  - Adicionada `LimpaDroidDatabase.MIGRATION_2_3` (com verificação de `knownAppDao()`) ao teste de migração v1 em `TrashRepositoryAndPurgeWorkerRobolectricTest.kt`, complementando o teste dedicado `MIGRATION_2_3` em `AndroidInstalledAppsRepositoryRobolectricTest.kt`.
  - Adicionado teste em `OrphanScanEngineTest.kt` comprovando que pastas-pai excluídas pelo usuário bloqueiam toda a travessia de candidatos órfãos internos.
- **Verificação final objetiva:** `gradle testDebugUnitTest lint assembleDebug --console=plain` concluído com sucesso (`86` testes em `24` suítes: `0` falhas, `0` erros, `0` ignorados; `0` erros de Lint; APK debug gerado).
- **Observações residuais para fases seguintes (sem bloqueio da Fase 4):** validar visibilidade real de pacotes e `MATCH_ARCHIVED_PACKAGES` em aparelho físico Android 15+/17 conforme `docs/ROTEIRO_TESTE.md`, e avaliar agrupamento opcional por aplicativo removido além do agrupamento por regra em `CategoryResultsScreen`.
- **Nota final:** 9,3 / 10

---

## Fase 5 — Arquivos duplicados
- **Entregas avaliadas:** pipeline em 4 estágios em `DuplicateScanEngine` (varredura iterativa sem links simbólicos e exclusão de ocultos/`.thumbnails`/lixeira/`Android/` exceto mídia WhatsApp §7.5; agrupamento por tamanho exato com mínimo configurável; hash parcial SHA-256 de 64 KiB iniciais/finais + tamanho com paralelismo limitado a 4; hash completo SHA-256 em buffer de 1 MiB com cache `hash_cache` em lote via `RoomHashCacheRepository` reaproveitando `size` e `mtime`; revalidação imediata de tamanho, `mtime` e SHA-256 em `TrashRepository.moveToTrash`); política `DuplicateKeepPolicy` (menor índice na lista ordenada de pastas de prioridade, menor `lastModified`, menor caminho lexicográfico; selo **"Sem pasta de prioridade"** em confiança `MEDIUM`); invariante de preservação de ao menos uma cópia no domínio, `ScanRepository`, `CategoryResultsViewModel` e `TrashRepository` (incluindo exclusão automática de exemplar solitário remanescente e contagem de excedentes no resumo da Home); `LimpaDroidDatabase` v4 (`MIGRATION_3_4`); configurações de tamanho mínimo e lista ordenada/editável de pastas de prioridade em `AppPreferencesRepository` / `SettingsScreen`; troca manual da cópia preservada (`"Preservar esta"`) em `CategoryResultsScreen`; testes unitários, de migração, desempenho (`DuplicatePerformanceTest`) e jornada Robolectric completa.
- **Nota inicial:** 8,4 / 10
- **Principais problemas apontados na auditoria:**
  1. Chamada à função `suspend Hasher.sha256` fora de corrotina em `LimpaDroidRobolectricTest.kt`.
  2. Ausência de filtragem prévia de cópias duplicadas ausentes em disco antes de `DuplicateKeepPolicy.partitionBatchPreservingAtLeastOne` em `TrashRepository.moveCandidatesToTrash`, bem como ausência de `MIGRATION_3_4` nos testes de migração legados v1/v2.
  3. Aplicação de `Modifier.clickable(enabled = false)` no cartão da cópia preservada (`isKept = true`) e `contentDescription` redundante no ícone `Lock` em `DuplicateCandidateItemRow` (`CategoryResultsScreen.kt`), mesclando o selo `"Preservada"` na árvore de semântica e duplicando leitura no TalkBack.
  4. Alocação fixa de `64 KiB` por arquivo pequeno em `Hasher.partialSha256` (`size <= 128 KiB`), elevando pressão de memória durante varreduras extensas.
  5. Condição de corrida no encerramento do banco Room em `@After` antes da desmontagem dos `ViewModel`s pelo `composeTestRule`, além da necessidade de rolagem e espera determinística em `LimpaDroidRobolectricTest.kt`.
- **Correções realizadas:**
  - Envolvida a chamada `Hasher.sha256` em `runBlocking` e incluída `LimpaDroidDatabase.MIGRATION_3_4` nos testes de migração Room v1/v2.
  - Filtrados registros duplicados ausentes no armazenamento antes do particionamento em lote no `TrashRepository`, preservando o invariante mesmo quando outra cópia do grupo sumiu do disco.
  - Removido `Modifier.clickable` de cartões preservados (`!isKept`) e ajustado `contentDescription = null` no ícone decorativo `Lock` em `CategoryResultsScreen.kt`.
  - Reduzida a alocação de `ByteBuffer` em `Hasher.partialSha256` para `size.toInt().coerceIn(1, PARTIAL_WINDOW_BYTES)`.
  - Encapsulado o fechamento do banco em `RuleChain` externo ao `composeTestRule`, com rolagem explícita em `results_list` e `waitUntil` determinístico na atualização do botão de limpeza em `LimpaDroidRobolectricTest.kt`.
- **Verificação final objetiva:** `gradle lint testDebugUnitTest assembleDebug --console=plain` concluído com sucesso (`105` testes em `27` suítes: `0` falhas, `0` erros, `0` ignorados — incluindo `DuplicatePerformanceTest` em árvore de `200.004` arquivos e cancelamento abaixo de `2 s`; `0` erros de Lint; APK debug gerado).
- **Observações residuais para fases seguintes (sem bloqueio da Fase 5):** validar desempenho real em dispositivo físico com volume extenso e executar o roteiro manual de duplicados em `docs/ROTEIRO_TESTE.md` ao término das fases de desenvolvimento.
- **Nota final:** 9,4 / 10

---

## Fase 6 — Mídia do WhatsApp, Configurações e Acabamento
- **Entregas avaliadas:**
  - Domínio e motor do WhatsApp (`WhatsAppMediaRules`, `WhatsAppScanEngine`): inspeção das 4 raízes (WhatsApp, WhatsApp Business e caminhos legados com deduplicação canônica), regex restrita a `Images`, `Video`, `Audio`, `Voice Notes` (incluindo `Sent` e subpastas semanais), opção `Animated Gifs` desativada por padrão, bloqueio estrito de `.Statuses`, `Backups`, `Databases`, `WhatsApp Documents`, `WhatsApp Stickers`, `WhatsApp Profile Photos`, `WhatsApp Bug Reports`, `.Shared`, `.Links`, `.trash` e itens ocultos; filtro configurável de idade (`7` a `730` dias, padrão `90`), confiança `HIGH` com primeira execução desmarcada por segurança (`autoPreselect = false`) e escolha lembrada em `DataStore` (`AppPreferencesRepository`), integrado ao `ScanWorker` (`ScanScope.WHATSAPP`) e à `HomeScreen`.
  - Interface, Configurações completas e Acessibilidade (`CategoryResultsScreen`, `SettingsScreen`, `DiagnosticScreen`, `OnboardingScreen`, `HistoryScreen`, `strings.xml`): aviso obrigatório da Seção 7.5 na tela de resultados e no diálogo de confirmação, controle de pré-seleção automática, suporte visual para arquivos de áudio, seção completa do WhatsApp em **Configurações** (controle deslizante e atalhos `7..730` dias, switches por tipo e pré-seleção), descrição semântica TalkBack em `Switch`/`Slider`, marcação `heading()` em títulos de seção, alvos mínimos de toque de `48.dp` e contraste sólido sem transparência reduzida sobre tokens `tertiaryContainer`/`surface`.
- **Nota inicial:** 8,0 / 10
- **Principais problemas apontados na auditoria:**
  1. Divergências iniciais em `LimpaDroidRobolectricTest.kt` nos métodos consultados de DAO (`getForGroup`/`getByState`), string esperada de idade mínima (`"Idade mínima da mídia: 30 dias"`) e `waitUntil` aguardando nó fora da viewport antes da rolagem da `LazyColumn`.
  2. Ausência de `semantics { contentDescription = ... }` explícita nos componentes `Switch` em `CategoryResultsScreen.kt` e `SettingsScreen.kt` para leitura direta pelo TalkBack.
  3. Transparência parcial em `WhatsAppResultsNoticeCard` e falta de superfície de contêiner dedicada (`tertiaryContainer`/`onTertiaryContainer`) no aviso do `CleanupConfirmationDialog`.
  4. Alvos de toque nos ícones da barra superior do `DiagnosticScreen.kt` abaixo da padronização `size(48.dp)`, botão de nova tentativa sem `heightIn(min = 48.dp)`, ausência de `heading()` semântico em `DiagnosticScreen`/`OnboardingScreen` e recurso órfão `R.string.cd_copy` após remoção de leitura duplicada no ícone de cópia.
- **Correções realizadas:**
  - Corrigidos os acessos de DAO, espera determinística (`results_whatsapp_warning_banner` + `results_bottom_bar`) e rolagem na `TrashScreen` dentro de `LimpaDroidRobolectricTest.kt`.
  - Adicionadas descrições semânticas nos `Switch`es, semântica `heading()`, alvos mínimos de `48.dp` e superfícies opacas de contraste em `CategoryResultsScreen.kt`, `SettingsScreen.kt`, `DiagnosticScreen.kt` e `OnboardingScreen.kt`.
  - Removido o recurso não utilizado `R.string.cd_copy` em `strings.xml`, zerando os avisos de `UnusedResources` no Lint.
- **Verificação final objetiva:** `gradle lint testDebugUnitTest assembleDebug --console=plain` concluído com sucesso (`119` testes em `29` suítes: `0` falhas, `0` erros, `0` ignorados; `0` erros de Lint; APK debug gerado).
- **Observações residuais para a Fase 7:** executar o roteiro manual completo em dispositivo físico e concluir assinatura de release, `release.yml` e tag `v1.0.0`.
- **Nota final:** 9,4 / 10

---

## Fase 7 — Auditoria Final e Lançamento (`v1.0.0`)
- **Entregas avaliadas:**
  - Fluxo completo de lançamento em `.github/workflows/release.yml` (gatilho em tags `v*`, `permissions: contents: write`, validação explícita dos 4 Secrets de assinatura `RELEASE_KEYSTORE_BASE64`/`KEY_ALIAS`/`STORE_PASSWORD`/`KEY_PASSWORD`, decodificação Base64 para arquivo temporário em `${RUNNER_TEMP}`, normalização opcional de alias quando `KEY_ALIAS != "upload"` preservando `signingConfigs.release`, derivação de `VERSION_CODE = github.run_number` e `VERSION_NAME = ${GITHUB_REF_NAME#v}`, execução de `lint testDebugUnitTest assembleRelease`, verificação por `apksigner verify --verbose --print-certs`, geração de `.sha256`, limpeza garantida `if: always()` do keystore temporário e publicação via `softprops/action-gh-release@v3.0.3`).
  - Endurecimento de build e documentação de distribuição: atualização de `app/proguard-rules.pro` (atributos `SourceFile,LineNumberTable` e proteção das classes `@Serializable` de rotas e `TrashMetadataPayload` sob R8), fallback `versionName = "1.0.0"` em `app/build.gradle.kts`, deduplicação de `concurrency` por ramo no `ci.yml`, `README.md` com instruções de `keytool`/Secrets/release/instalação, `DECISOES.md` e `docs/ROTEIRO_TESTE.md`.
- **Nota inicial:** 8,5 / 10
- **Principais problemas apontados na auditoria:**
  1. `app/proguard-rules.pro` ainda estava em estado padrão com regras comentadas, expondo classes `@Serializable` (rotas Navigation Compose e `TrashMetadataPayload` da Lixeira) ao risco de remoção/ofuscação de metadados em `assembleRelease` com `isMinifyEnabled = true`.
  2. `app/build.gradle.kts` mantinha fallback padrão `versionName = "0.1.0"` e `ci.yml` usava `concurrency.group` por `github.ref`, permitindo execuções duplicadas entre `push` em `feat/**` e `pull_request` em `main`.
  3. Falta inicial de verificação empírica de `assembleRelease` + `apksigner verify` + `aapt2 dump permissions` e da lógica de normalização de alias quando `KEY_ALIAS != "upload"`.
- **Correções realizadas:**
  - Configuradas as regras de manutenção no `app/proguard-rules.pro` para `SourceFile`/`LineNumberTable`, `io.github.km20197.limpadroid.ui.navigation.**` e `TrashMetadataPayload**`.
  - Atualizado fallback de `versionName` para `"1.0.0"`, unificado `concurrency.group` em `ci.yml` e criado `.github/workflows/release.yml` completo com ações fixadas nas versões exatas da Seção 12.7.
  - Executada simulação integral do fluxo de assinatura em ambiente temporário isolado: geração de keystore com alias customizado, codificação/decodificação Base64, `keytool -changealias`, `gradle assembleRelease --console=plain` com R8 (`3,7 MiB`), `apksigner verify` aprovado (APK Signature Scheme v2) e `aapt2 dump permissions` confirmando ausência total de `INTERNET`.
- **Verificação final objetiva:** `compile_applet` (debug APK), `lint` (`0` erros), `gradle testDebugUnitTest --console=plain` (`119` testes em `29` suítes: `0` falhas, `0` erros, `0` ignorados) e `gradle assembleRelease` + `apksigner verify` concluídos com sucesso.
- **Pendência exclusiva de hardware do solicitante:** assinatura real nos GitHub Secrets do repositório remoto, disparo da tag `v1.0.0` no GitHub e execução do roteiro manual (`docs/ROTEIRO_TESTE.md`) no aparelho físico com Android 17.
- **Nota final:** 9,5 / 10



