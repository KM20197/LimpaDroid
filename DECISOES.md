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

### 12.6 Visibilidade de pacotes e `getInstalledPackages` (`QUERY_ALL_PACKAGES` avaliada na Fase 4)
- **Avaliação técnica (Fase 4):** Consultas pontuais via `<queries>` não permitem enumerar pacotes arbitrários instalados no dispositivo. Sem `QUERY_ALL_PACKAGES`, o filtro de visibilidade de pacotes introduzido no Android 11 (API 30) oculta aplicativos de terceiros do resultado de `PackageManager.getInstalledPackages`, o que faria com que pastas legítimas em `Android/media/<pacote>` ou na raiz pertencentes a aplicativos atualmente instalados fossem classificadas incorretamente como dados órfãos.
- **Decisão definitiva:** Declarar `QUERY_ALL_PACKAGES` no `AndroidManifest.xml` a partir da Fase 4 (aceitável conforme Seção 4, pois a distribuição do LimpaDroid é exclusivamente pessoal fora da Google Play), utilizando `PackageManager.PackageInfoFlags.of(MATCH_UNINSTALLED_PACKAGES or MATCH_ARCHIVED_PACKAGES)` em API 35+, `MATCH_UNINSTALLED_PACKAGES` em API 33–34 e fallback `Int` em API 30–32, além de checar `applicationInfo?.isArchived == true` em API 35+ para que aplicativos arquivados ou com dados retidos jamais sejam considerados removidos.

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
8. **Tema, dependências e restrições do AI Studio:** Cores dinâmicas (API 31+) com paleta própria de fallback; sem rede/Firebase/permissão `INTERNET`; `material-icons-extended` e `kotlinx-serialization` mantidos com `isMinifyEnabled = true`, `isShrinkResources = true` e regras em `app/proguard-rules.pro` na `release`. No ambiente AI Studio, `metadata.json` (`MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`) e `signingConfigs` permanecem inalterados por exigência da plataforma; para suportar qualquer `KEY_ALIAS` cadastrado em GitHub Secrets sem alterar `signingConfigs.release` (`keyAlias = "upload"`), o workflow `.github/workflows/release.yml` valida os 4 Secrets e normaliza o alias apenas na cópia temporária em `${RUNNER_TEMP}` antes do `assembleRelease` e da verificação por `apksigner verify`.
9. **Avaliação da Seção 14:** Executada como auditoria rigorosa em etapa separada neste chat ao final de cada fase, registrada em `docs/AVALIACOES.md`.
10. **Tipografia, diagnóstico e endurecimento do CI:** Fontes estáticas incompatíveis com pesos compostos (`Space Grotesk Light 300`) foram substituídas por `FontFamily.SansSerif` para títulos, mantendo `plus_jakarta_sans.ttf` e `jetbrains_mono.ttf` exclusivamente no peso `FontWeight.Normal` (400) com licença registrada em `licenses/OFL.txt`. A listagem diagnóstica ordena todas as entradas antes de aplicar o limite de visualização e sinaliza explicitamente quando há truncamento (`(lista truncada)`), tratando `DirectoryIteratorException` sem abortar o relatório. O `ci.yml` define `permissions: contents: read` e `concurrency` unificada por ramo (`github.head_ref || github.ref_name`) com `cancel-in-progress: true`.

---

## 3. Correções da Auditoria Técnica e Pendências

### 3.1 Riscos altos corrigidos
- **A1 (recuperação pós-movimentação na lixeira):** Em `TrashRepository.moveToTrash`, se `Files.move` já removeu a origem e o `payload` existe no destino, exceções e `CancellationException` ocorridas ao gravar `metadata.json` ou no Room são recuperadas em bloco `NonCancellable` promovendo a entrada para `IN_TRASH` antes de relançar `CancellationException`. A rotina `reconcilePendingAndRebuildIndex` passa a recuperar também registros `FAILED` cujo `payload` válido esteja presente.
- **A2 (validação estrita de `trashPath` e `metadata.json`):** `TrashStorage` exige que o caminho canônico de qualquer item da lixeira siga exatamente `<trashRoot>/<aaaaMMdd>/<uuid>/payload` (inclusive contra travessia `..` e links simbólicos), ignora `trashPath` lido de `metadata.json` derivando-o do diretório físico da entrada, e exige que `metadata.id` coincida com o nome do diretório `<uuid>`.
- **A3 (proteção na detecção de órfãos):** `AndroidInstalledAppsRepository` sinaliza `isReliable = false` em falhas de consulta ao `PackageManager`, conjunto vazio ou queda brusca em relação ao histórico ativo em `known_apps` sem marcar aplicativos existentes como removidos; `OrphanScanEngine` aborta a varredura quando `isReliable == false`. `ORPHAN_MEDIA_PKG` recebe confiança `HIGH` (`preselected = true`) somente quando há correspondência em `known_apps` com `removedAt` definido; caso contrário recebe `MEDIUM` (`preselected = false`).
- **A4 (exclusão mútua nas operações da lixeira):** `TrashRepository` serializa movimentação, restauração, exclusão definitiva, esvaziamento, expurgo e reconciliação/reconstrução com um `Mutex` único.

### 3.2 Riscos médios corrigidos e confirmação M8
- **M1:** Restauração move sem `REPLACE_EXISTING` e reitera sufixos `(restaurado)` diante de `FileAlreadyExistsException`.
- **M2:** Cópia de contingência na lixeira valida espaço livre via `Files.getFileStore(...).usableSpace` antes de copiar e retorna erro claro quando insuficiente.
- **M3:** O cálculo integral de SHA-256 ocorre apenas quando necessário (validação de duplicados com reuso do cache/hash já verificado ou cópia de contingência), e `MediaScannerConnection.scanFile` é invocado em lote único ao fim da operação em `moveCandidatesToTrash`.
- **M4:** `DuplicateScanEngine` executa o estágio 1 em duas passagens (primeiro contabilizando somente colisões de tamanho em mapa leve; depois coletando apenas os arquivos com colisão >= 2), e `WhatsAppScanEngine` emite candidatos em fluxo contínuo com travessia iterativa sem acumular toda a lista na memória.
- **M5:** Limpeza de lotes confirmados em `CategoryResultsScreen` passou a executar via `TrashCleanupWorker` no `WorkManager` (com `ForegroundInfo` tipo `dataSync`) e arquivo de parâmetros em `filesDir/cleanup_jobs`, sobrevivendo à saída da tela ou cancelamento do `viewModelScope`.
- **M6:** `ORPHAN_ROOT_KNOWN` deixou de inspecionar subpastas diretas de `Documents`, `Pictures`, `Movies`, `Music` e `Download`, restringindo-se a pastas diretas na raiz do armazenamento compartilhado.
- **M7:** Duplicados com empate no mesmo nível de prioridade ou contendo caminho em diretórios de projeto/dados de app (ex.: `.git`, `build`, `node_modules`, `android` aninhado) são rebaixados para confiança `MEDIUM` e mantidos desmarcados.
- **M8 (confirmado pelo solicitante em 21/11/2026):** Ricardo confirmou a manutenção das duas definições da Seção 2 desta nota: `minSdk = 30` (compatibilidade com Android 11+) e raiz da lixeira em `Documents/LimpaDroid/.lixeira`.

### 3.3 Riscos baixos
- **Tratados no código:** `PurgeWorker` propaga `CancellationException` em vez de converter em `Result.retry()`; `LimpaDroidDatabase` habilitou `exportSchema = true` com geração de `app/schemas/.../4.json` via KSP e teste automatizado de correspondência com o banco em execução; adicionados testes automatizados para `A1`–`A4`, `M1`–`M7`, cancelamento de `PurgeWorker`/`WorkManager` e metadados adulterados.
- **Restrições da plataforma / ações externas no repositório GitHub:** `metadata.json` (`MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API`), `signingConfigs` e `debug.keystore` são preservados por exigência do ambiente AI Studio; ajuste do bit de execução (`100755`) em `gradlew`, rebase das 5 branches do Dependabot, organização de commits por fase e publicação da tag de release (`v1.0.0`) devem ser executados diretamente no repositório Git/GitHub local.

### 3.4 Simplificação da tela inicial e licenciamento (solicitado em 21/11/2026)
- **Tela Início simplificada (2 ações principais):** os quatro botões individuais de análise na tela inicial foram unificados no botão **Varredura** (`ScanScope.FULL` via `FullScanEngine` executando sequencialmente dados supérfluos, órfãos, duplicados e mídia do WhatsApp em uma sessão única) acompanhado do botão **Limpeza total** (que executa limpeza confirmada em lote via `TrashCleanupWorker` apenas para os candidatos seguros atualmente pré-selecionados, mantendo `LOW`/`MEDIUM`, primeira execução do WhatsApp e cópias preservadas de duplicados intactos). As linhas compactas das 4 categorias permanecem clicáveis para inspeção e ajuste manual em `CategoryResultsScreen`.
- **Licenciamento:** adicionado arquivo `LICENSE` na raiz do repositório com o texto integral da licença **Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0)** em nome de **Ricardo Coutinho Mello**, com menção explícita no rodapé da tela **Início**, na tela **Configurações** e no `README.md` (preservando `licenses/OFL.txt` para as fontes embutidas).
