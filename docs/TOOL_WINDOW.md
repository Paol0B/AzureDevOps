# 🪟 Tool Window Pull Request - Documentazione

## 📋 Panoramica

Il plugin Azure DevOps ora include una **Tool Window dedicata** per gestire le Pull Request, simile alla finestra Commit di IntelliJ/Visual Studio, ma specializzata per Azure DevOps Pull Requests.

## 🎯 Caratteristiche

### 📊 Visualizzazione Pull Request
- **Tree View organizzato** per stato (Active, Completed, Abandoned)
- **Icone distintive** per ogni stato PR
- **Informazioni chiare**: #ID, titolo, branch source → target
- **Badge Draft** per PR in bozza
- **Contatori** per numero di PR per stato

### 🔍 Filtri Rapidi
- **Active**: PR attive (default)
- **Completed**: PR completate
- **Abandoned**: PR abbandonate
- **All**: Tutte le PR
- **My**: Solo le tue PR

### 📝 Pannello Dettagli
- **Titolo e ID** della PR
- **Descrizione completa** (supporto Markdown)
- **Branch**: source → target
- **Stato e creatore**
- **Link** per aprire nel browser
- **Azioni rapide**: Approve, Complete, Abandon

### ⚡ Azioni Disponibili
1. **Create New PR** - Crea nuova Pull Request
2. **Refresh** - Aggiorna lista PR
3. **Approve PR** - Approva PR selezionata
4. **Complete PR** - Completa PR (merge)
5. **Abandon PR** - Abbandona PR
6. **Open in Browser** - Apri PR su Azure DevOps

## 🎨 Layout

```
┌─────────────────────────────────────────────────────────┐
│ Azure DevOps Pull Requests                    [+] [⟳]  │
├─────────────────────┬───────────────────────────────────┤
│ PR List (30%)       │ Details Panel (70%)               │
│                     │                                   │
│ Filters:            │ ┌───────────────────────────────┐ │
│ • Active (5)        │ │ PR #123: Add new feature      │ │
│ • Completed (12)    │ │                                │ │
│ • Abandoned (2)     │ │ feature/xyz → main            │ │
│ • All               │ │ Status: Active                │ │
│ • My PRs            │ │ Created by: John Doe          │ │
│                     │ └───────────────────────────────┘ │
│ 📁 Active (5)       │                                   │
│   ├─ #123 Add...   │ Description:                      │
│   ├─ #122 Fix...   │ This PR adds...                   │
│   └─ #121 Update.. │                                   │
│                     │                                   │
│ 📁 Completed (12)   │ Actions:                          │
│   ├─ #120 Merge... │ [Approve] [Complete] [Abandon]    │
│   └─ #119 ...      │ [Open in Browser]                 │
│                     │                                   │
└─────────────────────┴───────────────────────────────────┘
```

## 🚀 Come Usare

### Aprire la Tool Window

1. **Dalla sidebar**: Click su "Azure DevOps" tab
2. **Da menu**: View → Tool Windows → Azure DevOps
3. **Shortcut**: (Personalizzabile nelle Keymap)

### Navigare le PR

1. **Espandi/Collassa gruppi**: Click su frecce 📁
2. **Seleziona PR**: Click su una PR nella lista
3. **Dettagli**: Vedi automaticamente i dettagli a destra

### Filtrare PR

1. Click sui pulsanti filtro in alto:
   - **Active**: Solo PR attive
   - **Completed**: Solo PR completate
   - **Abandoned**: Solo PR abbandonate
   - **All**: Tutte le PR
   - **My**: Solo le tue PR

### Creare Nuova PR

1. Click su **[+]** nella toolbar
2. Si apre il dialog Create Pull Request
3. Compila e conferma

### Refresh

1. Click su **[⟳]** per aggiornare la lista
2. Oppure usa il pulsante "Refresh" nella toolbar

### Azioni su PR

1. **Seleziona una PR** dalla lista
2. **Vedi i dettagli** nel pannello destro
3. **Click su azioni**:
   - **Approve**: Approva la PR
   - **Complete**: Completa e merge
   - **Abandon**: Chiudi senza merge
   - **Open in Browser**: Apri su Azure DevOps

## 🔧 Componenti Tecnici

### File Principali

```
toolwindow/
├── AzureDevOpsToolWindowFactory.kt    # Factory per tool window
├── AzureDevOpsToolWindow.kt           # Contenitore principale
├── PullRequestListPanel.kt            # Lista PR con filtri
├── PullRequestDetailsPanel.kt         # Dettagli e azioni
└── PullRequestTreeModel.kt            # Model per tree view
```

### Registrazione Plugin

```xml
<extensions defaultExtensionNs="com.intellij">
    <toolWindow 
        id="Azure DevOps"
        anchor="bottom"
        icon="AllIcons.Vcs.Vendors.Github"
        factoryClass="paol0b.azuredevops.toolwindow.AzureDevOpsToolWindowFactory"/>
</extensions>
```

### API Integration

```kotlin
// Carica PR dal server
fun loadPullRequests(filter: String) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(...) {
        override fun run(indicator: ProgressIndicator) {
            val prs = apiClient.getPullRequests(filter)
            updateUI(prs)
        }
    })
}
```

## 🎨 Icone e Stati

| Stato | Icona | Descrizione |
|-------|-------|-------------|
| Active | 🌿 (Branch) | PR attiva, in review |
| Completed | ✅ (Pause) | PR completata e merged |
| Abandoned | ⛔ (Stop) | PR abbandonata/chiusa |
| Draft | 📝 | PR in bozza |

## 💡 Best Practices

### Performance

1. **Lazy Loading**: Le PR vengono caricate solo quando necessario
2. **Background Tasks**: Tutte le operazioni API in background
3. **Caching**: I dati vengono cachati per ridurre le chiamate API

### UX

1. **Feedback Immediato**: Notifiche per ogni azione
2. **Error Handling**: Messaggi chiari in caso di errori
3. **Progress Indicator**: Mostra progresso durante il caricamento

### Organizzazione

1. **Raggruppamento**: PR organizzate per stato
2. **Ordinamento**: Per data di creazione (più recenti prima)
3. **Contatori**: Numero di PR per categoria

## 🔄 Workflow Tipico

### Scenario 1: Review di PR

```
1. Apri Tool Window Azure DevOps
2. Filtra "Active" (default)
3. Seleziona PR da revieware
4. Leggi descrizione e dettagli
5. Click "Open in Browser" per vedere il diff
6. Torna all'IDE
7. Click "Approve" per approvare
8. Notifica di successo ✅
```

### Scenario 2: Merge di PR

```
1. Seleziona PR completata dal review
2. Verifica che sia approvata
3. Click "Complete"
4. Conferma il merge
5. PR spostata in "Completed" ✅
```

### Scenario 3: Creazione PR

```
1. Click [+] nella toolbar
2. Dialog Create PR si apre
3. Compila titolo, descrizione, branch
4. Click OK
5. PR creata e appare nella lista "Active" ✅
```

## 🆚 Confronto con Visual Studio

### Similitudini
- ✅ Tool Window dedicata
- ✅ Split view (lista + dettagli)
- ✅ Filtri rapidi
- ✅ Azioni inline
- ✅ Integrazione seamless

### Differenze
- 🎯 Ottimizzato per JetBrains IDEs
- 🎨 UI coerente con IntelliJ Platform
- ⚡ Più veloce (nativo Kotlin)
- 🔍 Integrato con Git4Idea

## 🐛 Troubleshooting

### "Tool Window non appare"
- Verifica che il repository sia Azure DevOps
- Controlla View → Tool Windows → Azure DevOps
- Riavvia l'IDE

### "Nessuna PR visualizzata"
- Verifica la connessione (Test Connection)
- Controlla il filtro selezionato
- Click "Refresh" per ricaricare

### "Errore nel caricamento PR"
- Verifica il PAT nelle Settings
- Controlla i permessi del PAT
- Verifica la connessione internet

## 📊 Shortcut Consigliati

Personalizza in Settings → Keymap → Azure DevOps:

```
Open Azure DevOps Tool Window:  Alt+D
Refresh Pull Requests:          Ctrl+Alt+R
Create New PR:                  Ctrl+Alt+P
```

## 🎯 Estensioni Future

Funzionalità pianificate:
- [ ] Commenti inline su PR
- [ ] Diff viewer integrato
- [ ] Work items collegati
- [ ] Pipeline status
- [ ] Reviewer suggestions
- [ ] Conflict resolution
- [ ] Draft PR editing
- [ ] Template PR

---

**La Tool Window Azure DevOps porta l'esperienza di Pull Request management direttamente nel tuo IDE! 🚀**
