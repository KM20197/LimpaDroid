# LimpaDroid

Aplicativo Android 17 (API 37) de uso pessoal para análise e limpeza controlada de armazenamento compartilhado, 100% offline (sem permissão `INTERNET`) e com lixeira interna com retenção de 30 dias.

## Estado atual

- **Fase 0 (Base) concluída:** estrutura Gradle (API 37, JDK 21), tema Material 3 claro/escuro com cores dinâmicas, navegação type-safe, onboarding de permissões (`MANAGE_EXTERNAL_STORAGE` obrigatória e `POST_NOTIFICATIONS` opcional), tela inicial responsiva, modo diagnóstico acionado por toque longo na versão (com cópia de relatório) e pipeline de CI (`.github/workflows/ci.yml`).

## Compilação local

Requisitos: JDK 21, Android SDK Platform 37 (`platforms;android-37`), Gradle 9.3.1.

```bash
gradle lint testDebugUnitTest assembleDebug
```

## Documentação complementar

- `DECISOES.md` — verificações da Seção 12, divergências técnicas e decisões registradas.
- `docs/ROTEIRO_TESTE.md` — roteiro de validação manual em dispositivo Android 17.
