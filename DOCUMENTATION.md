# Ubi Driver - Motor de Acessibilidade e Extração de Ganhos

Este projeto é uma ferramenta de auxílio para motoristas de aplicativo (Uber e 99), projetada para calcular a lucratividade líquida de ofertas de corridas em tempo real usando Serviços de Acessibilidade do Android.

## 🏗️ Arquitetura do Sistema

O projeto é dividido em duas camadas principais:
1.  **Nativo (Android/Java)**: Captura e processa dados brutos da tela.
2.  **Web/Hybrid (Capacitor/JS)**: Interface do usuário (Overlay) e lógica de cálculo financeiro.

---

## 🧠 Motor de Extração (GigUReaderService.java)

O coração do projeto é o `GigUReaderService`, que utiliza uma arquitetura de **3 Camadas de Fallback** para garantir que os dados sejam extraídos mesmo após atualizações de layout dos aplicativos.

### 1. Camada 1: View IDs (Precisão Máxima)
- Tenta localizar elementos específicos pelo `viewIdResourceName` (ex: `com.ubercab.driver:id/fare_amount`).
- É o método mais rápido e consome menos CPU.

### 2. Camada 2: Nós Âncora (Busca Contextual)
- Localiza textos fixos na tela ("Pagamento no app", "Dinheiro", "Cartão", "Voucher", "Pix").
- A partir da âncora, realiza uma **busca recursiva** (sobe até 3 níveis no pai e desce nos filhos) para encontrar o valor monetário associado.

### 3. Camada 3: Regex Fallback (Garantia de Leitura)
- Concatena todo o conteúdo textual de **TODAS as janelas visíveis** (`getWindows()`) em uma única string gigante.
- Aplica padrões de Expressão Regular (Regex) robustos para extrair:
    - **Preço**: Suporta formatos `R$ 10,00` (Uber) e `R$10,00` (99).
    - **Distância**: Acumula múltiplos trechos detectados na mesma tela (ex: 99 que mostra 840m + 3.2km).
    - **Tempo**: Acumula minutos de múltiplos trechos.
    - **Dinâmica (Surge)**: Captura multiplicadores (ex: 1.5x).

---

## 🛡️ Filtros e Segurança (Performance e Bateria)

Para evitar consumo excessivo de bateria e falso-positivos, o serviço implementa:
- **Filtro de Pacote Híbrido**: Processa o evento se o pacote de origem OU a janela ativa pertencerem à Uber ou 99. Isso garante a captura de **janelas flutuantes/popups** mesmo quando o app principal não é a janela ativa.
- **Guard de Relevância**: Antes do processamento das 3 camadas, verifica se o texto extraído contém palavras-chave essenciais (`R$`, `km`, `min`, `Aceitar`).
- **Deduplicação Inteligente**: Gera um hash único para cada oferta baseada nos dados extraídos. Se a mesma oferta disparar múltiplos eventos de acessibilidade, ela só é processada e emitida uma única vez.
- **Reciclagem de Memória**: Uso rigoroso de `.recycle()` em todos os nós de acessibilidade para prevenir Memory Leaks.

---

## 🔌 Ponte de Comunicação (GigUPlugin.java)

Utiliza o Capacitor para emitir eventos em tempo real para o código JavaScript:
- **Evento `onUberOffer`**: Envia um objeto JSON contendo `price`, `distanceKm`, `timeMin` e `surgeMultiplier`.

---

## 🛠️ Configurações Técnicas
- **Min SDK**: 26 (Android 8.0)
- **Janelas**: Utiliza `flagRetrieveInteractiveWindows` para enxergar popups de aplicativos terceiros.
- **Eventos**: Escuta `typeWindowStateChanged` (32) e `typeWindowContentChanged` (2048).

---

## 📝 Como Calibrar
O serviço loga no **Logcat** (TAG: `GigUReader`) a string `FULL_TEXT` sempre que uma janela relevante é processada. Para calibrar novos aplicativos ou mudanças de layout:
1. Abra o Android Studio.
2. Filtre por `FULL_TEXT`.
3. Copie a string da tela de oferta.
4. Ajuste os padrões de Regex em `GigUReaderService.java`.
