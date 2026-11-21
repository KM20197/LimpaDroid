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
1. Na tela **Início**, toque e segure no rodapé da versão (`Versão 1.0.0 (1) · Segure para diagnóstico`).
2. Verifique na seção **Estado do sistema** se `MANAGE_EXTERNAL_STORAGE: Concedido`, `Volume primário: Montado e acessível` e `Android/media: Acessível` aparecem confirmados.
3. Confira a raiz resolvida por `StorageManager`/`StorageVolume` e a seção **Raízes esperadas do WhatsApp** (caminhos e subpastas detectadas).
4. Toque em **Copiar** no topo da tela para copiar o relatório completo para a área de transferência e registre os resultados reais do dispositivo.

---

## Fase 3: Dados supérfluos e Lixeira
1. Crie no dispositivo arquivos sintéticos de teste em `/storage/emulated/0/`:
   - `DCIM/.thumbnails/teste_thumb.jpg`
   - `Download/download_antigo.part` (com data de modificação anterior a 7 dias via `touch -d "10 days ago"`)
   - `LOST.DIR/fragmento_teste`
   - `Download/pasta_vazia_teste/`
   - `Download/instalador_teste.apk`
   - `Documents/Protegido/nao_tocar.tmp`
2. Em **Configurações** (ícone de engrenagem na **Início**), cadastre `/storage/emulated/0/Documents/Protegido` em **Pastas e caminhos protegidos**.
3. Na **Início**, toque em **Varredura** (ou abra a categoria **Dados supérfluos** e toque em **Analisar**).
4. Na lista de resultados de **Dados supérfluos**, confirme que apenas os itens de confiança **Alta** (`THUMBS` e `PARTIAL_DL`) vêm pré-selecionados, enquanto **Média** (`LOST_DIR`, `EMPTY_DIR`) e **Baixa** (`OLD_APK`) vêm desmarcados, e que nenhum arquivo dentro de `Documents/Protegido` é listado.
5. Toque em **Mover para a lixeira** (ou utilize **Limpeza total** na **Início**), confirme o aviso de retenção de 30 dias e valide em **Lixeira** e **Histórico** que os itens movidos podem ser restaurados para o caminho original.

---

## Fase 4: Possíveis dados remanescentes de aplicativos removidos
1. **Registro histórico e remoção real:**
   - Com um aplicativo auxiliar instalado (ou via pacote de teste), abra o **LimpaDroid** na tela **Início** para sincronizar a tabela `known_apps`.
   - Crie uma pasta correspondente em `Documents/<Nome do Aplicativo>/rascunho.txt` e desinstale o aplicativo auxiliar mantendo o armazenamento compartilhado.
   - Em paralelo, crie:
     - `Android/media/com.exemplo.removido/dados.bin` (sem aplicativo instalado)
     - `net.exemplo.desconhecido/cache.dat` na raiz do armazenamento compartilhado
     - Uma pasta com formato de pacote ou nome pertencente a um aplicativo **arquivado** (Android 15+) e confirme que pastas de WhatsApp (`Android/media/com.whatsapp` / `WhatsApp`) existam no dispositivo.
2. **Execução da análise:**
   - Na tela **Início**, execute **Varredura** e toque na categoria **Possíveis dados remanescentes de aplicativos removidos** (ou use **Analisar** dentro da tela de resultados).
3. **Verificações obrigatórias:**
   - `Android/media/com.exemplo.removido` aparece em **Pacote ausente em Android/media** com confiança **Alta** e caixa **marcada**.
   - `Documents/<Nome do Aplicativo>` aparece em **Pasta de aplicativo removido no histórico** com confiança **Média**, caixa **desmarcada** e descrição exibindo nome do aplicativo, pacote e data de remoção.
   - `net.exemplo.desconhecido` aparece em **Pasta com formato de pacote na raiz** com confiança **Baixa** e caixa **desmarcada**.
   - Pastas de aplicativos instalados, aplicativos arquivados, pastas padrão do sistema, `Documents/LimpaDroid` e pastas do WhatsApp **não aparecem** nos resultados.
4. **Remoção e restauração:**
   - Selecione apenas o item desejado, toque em **Mover para a lixeira**, confirme a retenção de 30 dias e valide na tela **Lixeira** que a pasta inteira e seu conteúdo interno podem ser restaurados ao caminho original.

---

## Fase 5: Arquivos duplicados
1. **Preparação de arquivos de teste em `/storage/emulated/0/`:**
   - Crie um conteúdo idêntico com `>= 4 KiB` em dois locais distintos:
     - `DCIM/Camera/dup_prioridade.jpg` (pasta de prioridade padrão)
     - `Download/dup_excedente.jpg`
   - Crie outro par idêntico com `>= 4 KiB` exclusivamente fora das pastas de prioridade (por exemplo, `TempA/sem_prio_1.bin` e `TempB/sem_prio_2.bin`).
   - Crie também dois arquivos idênticos menores que `4 KiB` (`1 KiB`) em `Documents/peq_1.txt` e `Download/peq_2.txt`.
2. **Análise e política de preservação:**
   - Na tela **Início**, execute **Varredura** e toque na categoria **Arquivos duplicados** (ou use **Analisar** na tela da categoria).
   - Confirme que os arquivos menores que `4 KiB` não aparecem com o limite padrão.
   - No grupo com cópia em `DCIM/Camera`, verifique se a cópia em `DCIM/Camera` está marcada como **Preservada** (sem caixa de seleção) e a cópia em `Download` vem selecionada (`Alta`).
   - No grupo de `TempA`/`TempB`, confirme o selo **Sem pasta de prioridade**, confiança **Média** e todas as caixas inicialmente desmarcadas.
3. **Troca manual da cópia preservada e invariante:**
   - Toque em **Preservar esta** na outra cópia do grupo e confirme que a marcação **Preservada** alterna sem permitir selecionar todas as cópias simultaneamente.
   - Mova o excedente selecionado para a lixeira, confirme a retenção de 30 dias, valide na **Lixeira** a restauração do arquivo e confira em **Configurações** a alteração de tamanho mínimo e da lista ordenada de pastas de prioridade.

## Fase 6: Mídia do WhatsApp, Configurações e Acessibilidade
1. **Preparação de pastas e idades no dispositivo (sem tocar dados pessoais se usar árvore sintética ou cópias de teste):**
   - Em `Android/media/com.whatsapp/WhatsApp/Media/` (ou `WhatsApp Business`), prepare ou utilize cópias de teste com datas modificadas (`touch -d`):
     - `WhatsApp Images/IMG-ANTIGA.jpg` (> 90 dias) e `WhatsApp Images/Sent/IMG-RECENTE.jpg` (< 30 dias)
     - `WhatsApp Voice Notes/202601/PTT-ANTIGO.opus` (> 90 dias)
     - `WhatsApp Animated Gifs/GIF-ANTIGO.mp4` (> 90 dias)
     - Confirme a existência de pastas protegidas: `.Statuses/`, `Backups/`, `WhatsApp Documents/`, `WhatsApp Stickers/`.
2. **Primeira execução e regras de segurança:**
   - Na tela **Início**, execute **Varredura** e toque na categoria **Mídia do WhatsApp** (ou use **Analisar** na tela da categoria).
   - Confirme a exibição do aviso obrigatório de que arquivos removidos deixam de aparecer no WhatsApp até serem restaurados da lixeira.
   - Verifique que apenas arquivos com mais de 90 dias de `Images`, `Video`, `Audio` e `Voice Notes` são listados; `GIF-ANTIGO.mp4` e `.Statuses`/`Backups`/`WhatsApp Documents`/`WhatsApp Stickers` **não aparecem**.
   - Confirme que todas as caixas de seleção vêm **desmarcadas na primeira execução**, mesmo exibindo confiança **Alta**.
3. **Limpeza, verificação no WhatsApp e restauração:**
   - Selecione `IMG-ANTIGA.jpg`, toque em **Mover para a lixeira**, confira o aviso no diálogo e confirme.
   - Verifique no WhatsApp que a imagem de teste deixou de ser exibida localmente; em seguida, abra a **Lixeira** no **LimpaDroid**, restaure o arquivo e confirme que voltou ao caminho original com `lastModified` preservado.
4. **Configurações completas e escolha lembrada:**
   - Em **Configurações -> Mídia do WhatsApp**, ajuste a idade mínima para **30 dias**, habilite **GIFs animados (opcional)** e ative **Pré-selecionar mídias antigas**.
   - Refaça a análise do WhatsApp e confirme que `GIF-ANTIGO.mp4` passou a ser listado e que os itens elegíveis vêm pré-selecionados conforme a escolha salva.
5. **Acessibilidade e modo claro/escuro:**
   - Alterne o tema do sistema entre claro e escuro verificando legibilidade de cartões, avisos (`tertiaryContainer`) e chips de confiança.
   - Com o **TalkBack** ativo, percorra Início, Resultados, Lixeira, Histórico e Configurações confirmando leitura clara dos `Switch`es, controle deslizante, cabeçalhos e ausência de ações não rotuladas.

## Fase 7: Verificação de assinatura e lançamento `v1.0.0`
1. **Secrets e disparo do workflow:**
   - Confirme os 4 Secrets no GitHub (`RELEASE_KEYSTORE_BASE64`, `KEY_ALIAS`, `STORE_PASSWORD`, `KEY_PASSWORD`) e execute o push da tag `v1.0.0`.
   - Verifique na aba **Actions** do GitHub que o workflow `Release APK (Signed)` concluiu `lint`, `testDebugUnitTest`, `assembleRelease`, `apksigner verify` e publicou a Release com `limpadroid-1.0.0-release.apk` e `limpadroid-1.0.0-release.apk.sha256`.
2. **Integridade local opcional (antes de transferir para o telefone):**
   ```bash
   sha256sum -c limpadroid-1.0.0-release.apk.sha256
   apksigner verify --verbose --print-certs limpadroid-1.0.0-release.apk
   ```
3. **Instalação e validação completa no dispositivo:**
   - Instale `limpadroid-1.0.0-release.apk` no aparelho, execute o roteiro das Fases 0 a 6 em árvore controlada e confira o funcionamento com R8 ativado (navegação entre todas as telas, persistência de configurações, lixeira e restauração).
   - Opcionalmente, gere uma build posterior assinada com o **mesmo keystore** e instale por cima para comprovar manutenção do banco Room e da lixeira sem desinstalação.
