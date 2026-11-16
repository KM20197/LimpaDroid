# Registro de Decisões Técnicas e Divergências (Seção 12)

Este documento registra as verificações prévias da Seção 12 do Plano de Desenvolvimento e quaisquer divergências encontradas entre o plano inicial, a documentação oficial do Android 17 (API 37) e as decisões confirmadas pelo solicitante.

---

## 1. Verificações da Seção 12

### 12.1 Disponibilidade do SDK API 37 e versões compatíveis
- **Verificação:** A documentação oficial (`developer.android.com/about/versions/17/setup-sdk`) confirma a compilação contra Android 17 (API 37, *Cinnamon Bun*), exigindo AGP `8.9.0-rc01` ou superior e instalação da plataforma Preview via canal Canary quando necessário.
- **Stack definitivo adotado no projeto:**
  - Gradle `9.3.1`, AGP `9.1.1` (suporte nativo/embutido a Kotlin, sem o plugin legado `org.jetbrains.kotlin.android`), Kotlin `2.2.10`, KSP `2.3.5`, bytecode alvo em **Java/Kotlin `17`** (`sourceCompatibility`, `targetCompatibility` e `jvmTarget` em `17`).
  - **JVM de build/testes (divergência registrada):** O Robolectric `4.18` exige JVM `21` para criar o sandbox da API `37` (`UnsupportedOperationException` em `DefaultSdkProvider` quando executado sob JDK 17). Portanto, o workflow de CI (`.github/workflows/ci.yml`) executa o Gradle sob **JDK `21` (Temurin)** mantendo o bytecode compilado em **target `17`** compatível com Android 11+.
  - Jetpack Compose BOM `2026.08.00`, Navigation Compose `2.10.2`, Room `2.7.0` (KSP), WorkManager `2.12.0`, Kotlinx Serialization `1.9.0`, DataStore Preferences `1.1.7`, Robolectric `4.18` (com `--add-opens` nos testes unitários).
  - **`compileSdk = 37`, `targetSdk = 37`, `minSdk = 30`** (compatível com Android 11 ou superior, mantendo Android 17 como alvo principal).

### 12.2 Mudanças de comportamento do Android 17 e compatibilidade com `minSdk = 30`
- **Telas grandes (`sw >= 600dp`):** Para apps com `targetSdk = 37`, não há mais opção de desativar redimensionamento/orientação em telas grandes (`developer.android.com/about/versions/17/behavior-changes-17`). Todas as telas Compose adotam layout responsivo (`BoxWithConstraints`, `widthIn`, grade adaptativa).
- **Reflexão em `static final` e Native DCL:** Alteração reflexiva de campos `static final` lança `IllegalAccessException` na API 37, e carregamento dinâmico nativo exige bibliotecas somente leitura. O app não utiliza reflexão nem código nativo dinâmico.
- **Limites de memória por aplicativo:** O sistema aplica limites de memória baseados na RAM total. A inspeção diagnóstica limita entradas por nível (`maxEntriesPerLevel = 200`) e utiliza `LazyColumn`.
- **Armazenamento e guardas de API (`minSdk = 30`):**
  - `MANAGE_EXTERNAL_STORAGE` e `StorageVolume.getDirectory()` estão disponíveis desde a API 30 (Android 11), permitindo acessar o armazenamento compartilhado primário e `Android/media`, enquanto `Android/data` e `Android/obb` permanecem inacessíveis.
  - Cores dinâmicas (`dynamicLightColorScheme`/`dynamicDarkColorScheme`) utilizam guarda `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (API 31+).
  - Verificação e solicitação de `POST_NOTIFICATIONS` utilizam guarda `Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU` (API 33+).

### 12.3 Limite de tempo de `foregroundServiceType="dataSync"` e WorkManager
- **Verificação (`kb://android/develop/background-work/services/fgs/timeout`):** Serviços `dataSync` (incluindo o `SystemForegroundService` interno do `WorkManager`) compartilham limite cumulativo de **6 horas em 24 horas** (reiniciado ao trazer o app ao primeiro plano ou após 24h).
- **Implementação na Fase 1:** Declarado `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />` no `AndroidManifest.xml`, utilizado `ForegroundInfo(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)` no `ScanWorker` e implementado salvamento de checkpoint/subárvores no Room tanto periodicamente quanto no bloco `finally` (`NonCancellable`) em cancelamentos ou interrupções, permitindo retomada determinística sem duplicar candidatos (`UNIQUE(sessionId, path)`) nem inflar contadores parciais.

### 12.4 e 12.5 Acesso a `Android/media` e estrutura real do WhatsApp no dispositivo
- **Status:** Pendente de validação em hardware real (sem emulador/ADB no ambiente de geração). Será validado pelo solicitante no dispositivo físico por meio do **Modo Diagnóstico** entregue na Fase 0.

### 12.6 Visibilidade de pacotes e `getInstalledPackages` (`QUERY_ALL_PACKAGES` removido na Fase 0)
- **Decisão (Fase 0):** `QUERY_ALL_PACKAGES` foi removida do `AndroidManifest.xml` porque a identificação de possíveis dados remanescentes de aplicativos removidos só será implementada na **Fase 4**.
- **Critério para a Fase 4:** Avaliar primeiro se consultas específicas ou `<queries>` para pacotes conhecidos são suficientes; somente adicionar `QUERY_ALL_PACKAGES` se a detecção abrangente for tecnicamente indispensável, documentando a justificativa. Ao consultar pacotes instalados, utilizar `PackageManager.PackageInfoFlags.of(flags)` em API 33+ (com fallback `Int` para API 30–32) e verificar `applicationInfo?.isArchived == true` em API 35+.

### 12.7 Versões exatas das GitHub Actions e Wrapper do Gradle
- `actions/checkout@v7.0.1`
- `actions/setup-java@v6.3.0` (JDK `21`, distribuição Temurin, compilando bytecode Java/Kotlin `17` e habilitando sandbox Robolectric API `37`)
- `android-actions/setup-android@v4.0.4`
- `gradle/actions/setup-gradle@v6.4.1`
- `actions/upload-artifact@v7.0.2`
- `softprops/action-gh-release@v3.0.3`
- **Wrapper, keystore e canal do SDK no CI:** O Gradle Wrapper `9.3.1` (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`) foi gerado e versionado no repositório para que `ci.yml` execute `./gradlew lint testDebugUnitTest assembleDebug`. Como o ambiente AI Studio requer a preservação de `signingConfigs` (`debugConfig` apontando para `debug.keystore`) e o arquivo `debug.keystore.base64` não é versionado na branch `main` do GitHub, o workflow de CI gera temporariamente `debug.keystore` via `keytool` antes da compilação de depuração (mantendo a chave de lançamento separada em GitHub Secrets). Para instalar a plataforma Android 17 compatível com o AGP `9.1.1`, o `ci.yml` utiliza `sdkmanager --channel=3 "platforms;android-37.0" "build-tools;36.0.0"`.

---

## 2. Decisões definitivas consolidadas

1. **`applicationId` e pacote raiz:** `io.github.km20197.limpadroid` (identificador GitHub `KM20197` normalizado para minúsculas conforme convenção Android).
2. **SDK alvo e JDK:** `minSdk = 30`, `compileSdk = 37`, `targetSdk = 37`, alvo de bytecode `Java/Kotlin 17` (`sourceCompatibility`, `targetCompatibility` e `jvmTarget` em `17`), com JVM de execução no CI em `JDK 21` para suportar os testes Robolectric na API `37`.
3. **Raiz de armazenamento (`StorageManager` / `StorageVolume`):** Resolvida dinamicamente por `StorageVolumeResolver` (`StorageManager.primaryStorageVolume.directory`), validando volume montado e permissões. Escopo da v1 limitado ao armazenamento interno compartilhado primário (`Android/media` incluído; cartão SD, USB/OTG, `Android/data` e dados privados internos fora do escopo).
4. **Arquitetura definitiva da Lixeira (Fase 2):** Lixeira própria, unificada, sob controle do LimpaDroid em `<armazenamento_primário>/Documents/LimpaDroid/.lixeira/` com arquivo `.nomedia`, subdiretório por item contendo o arquivo e `metadata.json`, além de índice operacional no Room para reconstrução após atualização ou reinstalação. Movimentação via `Files.move(..., ATOMIC_MOVE)` quando suportado; em fallback, cópia seguida de verificação por hash SHA-256 e registro de estado antes de excluir a origem. Retenção mínima de 30 dias antes do expurgo pelo WorkManager. (Não utilizar `Android/media/<applicationId>/.lixeira`, pois diretórios obtidos por `getExternalMediaDirs()` são removidos na desinstalação.)
5. **Nomenclatura da categoria de órfãos:** **"Possíveis dados remanescentes de aplicativos removidos"**.
6. **Cronograma técnico definitivo das fases:**
   - **Fase 0:** Correção da infraestrutura existente (CI, Wrapper, assinatura, JDK 17, `minSdk = 30`, remoção de `QUERY_ALL_PACKAGES`, resolução via `StorageManager`/`StorageVolume`).
   - **Fase 1:** Infraestrutura de varredura (scanner recursivo, classificação, persistência, controle de execução, cancelamento e retomada).
   - **Fase 2:** Núcleo da Lixeira (`Documents/LimpaDroid/.lixeira`, movimentação segura, índice, recuperação, histórico, metadados e retenção de 30 dias).
   - **Fase 3:** Arquivos supérfluos.
   - **Fase 4:** Possíveis dados remanescentes de aplicativos removidos (avaliação de pacotes, proteção de arquivados e revisão de `QUERY_ALL_PACKAGES`).
   - **Fase 5:** Arquivos duplicados (agrupamento por tamanho, SHA-256, comparação de conteúdo e preservação de ao menos uma cópia).
   - **Fase 6:** Mídia do WhatsApp (identificação recursiva de imagens, vídeos e áudios de WhatsApp e WhatsApp Business; `preselected = false` na primeira execução prevalecendo sobre a regra geral de confiança `HIGH`).
   - **Fase 7:** Auditoria final e lançamento (testes completos, validação em aparelhos físicos e APK assinado no GitHub Releases).
7. **Onboarding (`POST_NOTIFICATIONS`) e Modo Diagnóstico:** `POST_NOTIFICATIONS` opcional em Android 13+; toque longo no rodapé de versão da tela **Início** abre o Modo Diagnóstico com botão de cópia.
8. **Tema, dependências e restrições do AI Studio:** Cores dinâmicas (API 31+) com paleta própria de fallback; sem rede/Firebase/permissão `INTERNET`; `material-icons-extended` e `kotlinx-serialization` mantidos com `isMinifyEnabled = true` e `isShrinkResources = true` na `release`. No ambiente AI Studio, `metadata.json` (`MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`) e `signingConfigs` permanecem inalterados por exigência da plataforma; ao exportar para o GitHub / Fase 7, adaptar `keyAlias` via variável de ambiente `KEY_ALIAS`.
9. **Avaliação da Seção 14:** Executada como auditoria rigorosa em etapa separada neste chat ao final de cada fase.
