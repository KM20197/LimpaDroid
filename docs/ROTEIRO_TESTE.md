# Roteiro de Teste Manual — LimpaDroid

## Fase 0: Validação inicial no dispositivo (Android 11 a 17)

### 1. Instalação do APK debug
Baixe o artefato `limpadroid-debug-apk` gerado pelo workflow `ci.yml` no GitHub Actions ou compile localmente (gerando `debug.keystore` com `keytool` caso ainda não exista na raiz):

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Onboarding de permissões
1. Abra o **LimpaDroid**.
2. Confirme que o botão **Continuar para o Início** inicia desabilitado enquanto o acesso amplo estiver pendente.
3. Toque em **Abrir configurações de acesso** e conceda o acesso a todos os arquivos na tela do sistema.
4. Retorne ao aplicativo e verifique se o estado mudou automaticamente para **Permissão concedida**.
5. Em Android 13+, teste o fluxo opcional de notificações (conceder ou selecionar **Continuar sem notificações** e confirmar a exibição do aviso).
6. Toque em **Continuar para o Início**.

### 3. Modo Diagnóstico (verificação dos itens 12.4 e 12.5)
1. Na tela **Início**, toque e segure no rodapé da versão (`Versão 0.1.0 (1) · Segure para diagnóstico`).
2. Verifique na seção **Estado do sistema** se `MANAGE_EXTERNAL_STORAGE: Concedido`, `Volume primário: Montado e acessível` e `Android/media: Acessível` aparecem confirmados.
3. Confira a raiz resolvida por `StorageManager`/`StorageVolume` e a seção **Raízes esperadas do WhatsApp** (caminhos e subpastas detectadas).
4. Toque em **Copiar** no topo da tela para copiar o relatório completo para a área de transferência e registre os resultados reais do dispositivo.

---
*(Os cenários das Fases 1 a 6 serão adicionados conforme a evolução do cronograma definitivo.)*
