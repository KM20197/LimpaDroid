# LimpaDroid

Aplicativo Android pessoal (Android 11 a Android 17 — `minSdk = 30`, `targetSdk = 37`) para análise e limpeza controlada do armazenamento interno compartilhado primário, 100% offline (sem permissão `INTERNET`) e com lixeira interna própria com retenção mínima de 30 dias.

## Estado atual

- **Fase 0 (Base e infraestrutura) concluída:** estrutura Gradle (`minSdk = 30`, `compileSdk = 37`, `targetSdk = 37`, JDK `17`, Gradle Wrapper `9.3.1`), tema Material 3 claro/escuro com cores dinâmicas em API 31+, navegação type-safe, onboarding de permissões (`MANAGE_EXTERNAL_STORAGE` obrigatória e `POST_NOTIFICATIONS` opcional em API 33+), resolução dinâmica da raiz via `StorageManager`/`StorageVolume`, tela inicial responsiva, modo diagnóstico acionado por toque longo na versão (com cópia de relatório) e pipeline de CI (`.github/workflows/ci.yml`).

## Escopo e limitações da versão 1

- **Suportado:** armazenamento interno compartilhado primário e `Android/media`.
- **Fora do escopo (v1):** cartão SD, unidades USB/OTG, `Android/data` e dados privados internos de outros aplicativos.
- **Lixeira própria (Fase 2):** prevista em `Documents/LimpaDroid/.lixeira` (com `.nomedia`, metadados em JSON por item e índice Room para recuperação inclusive após reinstalação).

## Compilação local

Requisitos: JDK 17, Android SDK Platform 37 (`platforms;android-37` via canal Canary quando em Preview), Gradle Wrapper (`9.3.1`). Caso `debug.keystore` não exista na raiz do repositório clonado, decodifique-o antes de compilar:

```bash
base64 -d debug.keystore.base64 > debug.keystore
./gradlew lint testDebugUnitTest assembleDebug
```

## Documentação complementar

- `DECISOES.md` — verificações técnicas, divergências e decisões definitivas registradas.
- `docs/ROTEIRO_TESTE.md` — roteiro de validação manual em dispositivo físico.
