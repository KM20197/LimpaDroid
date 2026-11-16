# Registro de Decisões Técnicas e Divergências (Seção 12)

Este documento registra as verificações prévias da Seção 12 do Plano de Desenvolvimento e quaisquer divergências encontradas entre o plano inicial, a documentação oficial do Android 17 (API 37) e as decisões confirmadas pelo solicitante.

---

## 1. Verificações da Seção 12

### 12.1 Disponibilidade do SDK API 37 e versões compatíveis
- **Verificação:** A documentação oficial (`developer.android.com/about/versions/17/setup-sdk`) confirma o Android 17 (API 37, *Cinnamon Bun*) em estágio estável (junho de 2026), exigindo AGP `8.9.0-rc01` ou superior.
- **Stack adotado no projeto:**
  - Gradle `9.3.1`, AGP `9.1.1` (suporte nativo/embutido a Kotlin, sem o plugin legado `org.jetbrains.kotlin.android`), Kotlin `2.2.10`, KSP `2.3.5`, JDK `21`.
  - Jetpack Compose BOM `2026.08.00` (Compose `1.12.x`), Navigation Compose `2.10.2`, Kotlinx Serialization `1.9.0`, DataStore Preferences `1.1.7`, Robolectric `4.18` (suporte oficial à API 37, com `--add-opens` para JDK 21 nos testes unitários).
  - `compileSdk = 37`, `targetSdk = 37`, `minSdk = 37` fixados conforme confirmação do solicitante.

### 12.2 Mudanças de comportamento do Android 17
- **Telas grandes (`sw >= 600dp`):** Para apps com `targetSdk = 37`, não há mais opção de desativar redimensionamento/orientação em telas grandes (`developer.android.com/about/versions/17/behavior-changes-17`). Todas as telas Compose adotam layout responsivo (`BoxWithConstraints`, `widthIn`, grade adaptativa).
- **Reflexão em `static final` e Native DCL:** Alteração reflexiva de campos `static final` lança `IllegalAccessException` na API 37, e carregamento dinâmico nativo exige bibliotecas somente leitura. O app não utiliza reflexão nem código nativo dinâmico.
- **Limites de memória por aplicativo:** O sistema aplica limites de memória baseados na RAM total. A inspeção diagnóstica limita entradas por nível (`maxEntriesPerLevel = 200`) e utiliza `LazyColumn`.
- **Armazenamento:** `MANAGE_EXTERNAL_STORAGE` mantém acesso amplo ao armazenamento compartilhado, incluindo `Android/media`, permanecendo `Android/data` e `Android/obb` inacessíveis.

### 12.3 Limite de tempo de `foregroundServiceType="dataSync"` e WorkManager
- **Verificação (`kb://android/develop/background-work/services/fgs/timeout`):** Serviços `dataSync` (incluindo o `SystemForegroundService` interno do `WorkManager`) compartilham limite cumulativo de **6 horas em 24 horas** (reiniciado ao trazer o app ao primeiro plano ou após 24h).
- **Divergência com a Seção 7.1 do plano:** O plano menciona tratar `onTimeout` no `ScanWorker`. No entanto, usando `WorkManager` com `setForeground`, quem implementa `Service.onTimeout(int, int)` é a classe interna `androidx.work.impl.foreground.SystemForegroundService`, não o `CoroutineWorker`. O Worker recebe cancelamento cooperativo (`CancellationException` / `onStopped()`).
- **Decisão:** Na Fase 1 (quando `WorkManager` for ativado), declarar `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge" />` no `AndroidManifest.xml`, informar `FOREGROUND_SERVICE_TYPE_DATA_SYNC` no `ForegroundInfo` e persistir o estado/progresso no tratamento de cancelamento (`finally` / `onStopped()`) do `ScanWorker`.

### 12.4 e 12.5 Acesso a `Android/media` e estrutura real do WhatsApp no dispositivo
- **Status:** Pendente de validação em hardware real (sem emulador/ADB no ambiente de geração). Será validado pelo solicitante no dispositivo Android 17 por meio do **Modo Diagnóstico** entregue na Fase 0.

### 12.6 `getInstalledPackages` com `MATCH_ARCHIVED_PACKAGES` e `MATCH_UNINSTALLED_PACKAGES` na API 37
- **Divergência/refinamento de API:** A sobrecarga `getInstalledPackages(Int)` é obsoleta desde a API 33. Na API 37, deve-se utilizar `getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))` com `Long`. Além disso, `MATCH_ARCHIVED_PACKAGES` sobrepõe-se a `MATCH_UNINSTALLED_PACKAGES` no AOSP e o estado arquivado pode ser confirmado via `applicationInfo?.isArchived == true`. Será aplicado na Fase 4.

### 12.7 Versões exatas das GitHub Actions
- `actions/checkout@v7.0.1`
- `actions/setup-java@v6.3.0`
- `android-actions/setup-android@v4.0.4`
- `gradle/actions/setup-gradle@v6.4.1`
- `actions/upload-artifact@v7.0.2`
- `softprops/action-gh-release@v3.0.3`
- **Divergência operacional no CI:** O repositório inicial não possui script `gradlew` nem binário `gradle-wrapper.jar` versionado (apenas `gradle/wrapper/gradle-wrapper.properties`). Por isso, `ci.yml` utiliza `gradle/actions/setup-gradle@v6.4.1` com `gradle-version: "9.3.1"` e executa `gradle lint testDebugUnitTest assembleDebug` diretamente.

---

## 2. Decisões confirmadas para a Fase 0

1. **`applicationId` e pacote raiz:** `io.github.km20197.limpadroid` (identificador GitHub `KM20197` normalizado para minúsculas conforme convenção Android).
2. **SDK alvo:** `compileSdk = 37`, `targetSdk = 37`, `minSdk = 37`.
3. **Onboarding (`POST_NOTIFICATIONS`):** Opcional; o usuário pode pular ou negar a permissão de notificações com exibição de aviso e prosseguir para o Início desde que `MANAGE_EXTERNAL_STORAGE` esteja concedida.
4. **Modo Diagnóstico:** Exibido mediante toque longo no rodapé de versão na tela **Início**, listando 2 níveis de `Android/media` e de `/storage/emulated/0`, além do estado das 4 raízes esperadas do WhatsApp, com botão de cópia para a área de transferência.
5. **Tema e dependências:** Cores dinâmicas do Android com paleta própria de fallback (claro/escuro); removidas todas as dependências de rede/Firebase do template inicial (sem permissão `INTERNET`).
6. **Avaliação da Seção 14:** Executada como auditoria rigorosa em etapa separada neste chat ao final de cada fase.
