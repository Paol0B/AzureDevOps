# Azure DevOps Integration Plugin

Plugin per JetBrains IDEs (IntelliJ IDEA, Rider, PyCharm, ecc.) che permette di creare Pull Request su Azure DevOps direttamente dall'IDE.

## 🎯 Funzionalità

- ✅ **Tool Window dedicato** per visualizzare e gestire le Pull Request
- ✅ **Visualizzazione PR** con lista organizzata per stato (Active, Completed, Abandoned)
- ✅ **Dettagli PR completi** - titolo, descrizione, branch, author, reviewers
- ✅ **Filtri intelligenti** - mostra solo PR attive o tutte
- ✅ **Rilevamento Automatico** del repository Azure DevOps dall'URL Git remoto
- ✅ **Creazione Pull Request** verso main/master dal proprio IDE
- ✅ **Selezione Branch** con autocompletamento dai branch locali e remoti
- ✅ **Configurazione Semplificata** - richiede solo il Personal Access Token (PAT)
- ✅ **Auto-Detection** di Organization, Project e Repository dall'URL Git
- ✅ **Notifiche** per feedback immediato
- ✅ **Link Diretto** alla PR creata nel browser
- ✅ **Compatibilità** con URL HTTPS e SSH di Azure DevOps
- ✅ **Visibilità Intelligente** - tool window e actions appaiono solo per repository Azure DevOps

## 📋 Requisiti

### 1. Repository Azure DevOps

Il plugin funziona **solo con repository clonati da Azure DevOps**. Deve rilevare automaticamente uno di questi formati di URL:

**HTTPS:**
- `https://dev.azure.com/{organization}/{project}/_git/{repository}`
- `https://{organization}.visualstudio.com/{project}/_git/{repository}`

**SSH:**
- `git@ssh.dev.azure.com:v3/{organization}/{project}/{repository}`
- `{organization}@vs-ssh.visualstudio.com:v3/{organization}/{project}/{repository}`

Il plugin rileva automaticamente **Organization**, **Project** e **Repository** dall'URL del remoto Git.

### 2. Personal Access Token (PAT)

Devi creare un Personal Access Token su Azure DevOps con i seguenti permessi:

1. Vai su Azure DevOps → User Settings → Personal Access Tokens
2. Clicca "New Token"
3. Seleziona i permessi:
   - **Code**: Read & Write
   - **Pull Request**: Read & Write
4. Copia il token generato (lo userai nella configurazione)

### 2. Repository Azure DevOps

Il repository del tuo progetto deve essere clonato da Azure DevOps. Il plugin rileva automaticamente le informazioni dall'URL remoto Git.

**Non è necessario configurare manualmente** organization, project o repository!

## 🚀 Installazione

### Opzione 1: Da Marketplace (quando pubblicato)
1. Apri IDE → Settings → Plugins
2. Cerca "Azure DevOps Integration"
3. Clicca Install e riavvia l'IDE

### Opzione 2: Build locale
```bash
# Clona il repository
git clone https://github.com/paol0b/azuredevops-plugin.git
cd azuredevops-plugin

# Build del plugin
./gradlew buildPlugin

# Il file .zip sarà in build/distributions/
```

Poi installa manualmente:
1. Settings → Plugins → ⚙️ → Install Plugin from Disk
2. Seleziona il file `.zip` generato

## ⚙️ Configurazione

### Prima configurazione

1. **Assicurati di avere un repository Azure DevOps clonato**
   ```bash
   # Esempio clone HTTPS
   git clone https://dev.azure.com/mycompany/MyProject/_git/my-repo
   
   # Esempio clone SSH
   git clone git@ssh.dev.azure.com:v3/mycompany/MyProject/my-repo
   ```

2. **Apri il progetto nel tuo IDE JetBrains**

3. **Vai nelle Settings**
   - File → Settings su Windows/Linux
   - IntelliJ IDEA → Preferences su macOS
   - Oppure: `Ctrl+Alt+S` (Win/Linux) / `Cmd+,` (Mac)

4. **Naviga in Tools → Azure DevOps**

5. **Verifica il repository rilevato**
   - Dovresti vedere: "Detected Repository: **mycompany/MyProject/my-repo**"
   - Se non appare, il repository non è di Azure DevOps o l'URL remoto non è riconosciuto

6. **Inserisci solo il Personal Access Token (PAT)**
   - Incolla il token creato precedentemente

7. **Clicca Test Connection** per verificare che tutto funzioni

8. **Clicca Apply → OK**

> 💡 **Nota**: Organization, Project e Repository vengono rilevati automaticamente dall'URL Git remoto!

### Esempio configurazione

```
✅ Detected Repository: mycompany/MyProject/my-repo
   Auto-rilevato dall'URL Git remoto

🔑 Personal Access Token: ••••••••••••••••••••
```

## 📝 Utilizzo

### Visualizzare Pull Request

1. **Apri il Tool Window "Pull Requests"**
   - Si trova nella barra inferiore dell'IDE (come il tab Commit/Git/Terminal)
   - Oppure: View → Tool Windows → Pull Requests
   - Shortcut: Personalizzabile nelle Keymap

2. **Esplora le PR**
   - **Lista PR**: Visualizza tutte le PR organizzate per stato (Active, Completed, Abandoned)
   - **Dettagli PR**: Click su una PR per vedere titolo, descrizione, branch, author, reviewers e status
   - **Filtro**: Toggle "Show Only Active" per mostrare solo PR attive

3. **Azioni disponibili**
   - **New Pull Request**: Crea una nuova PR
   - **Refresh**: Aggiorna la lista delle PR
   - **Open in Browser**: Apri la PR selezionata su Azure DevOps

### Creare una Pull Request

1. **Metodo 1 - Menu VCS:**
   - Vai su **VCS → Create Azure DevOps PR**

2. **Metodo 2 - Toolbar:**
   - Cerca l'icona nella toolbar VCS

3. **Nel Dialog:**
   - **Source Branch**: seleziona il branch da cui vuoi creare la PR (default: branch corrente)
   - **Target Branch**: seleziona il branch di destinazione (default: main o master)
   - **Title**: inserisci il titolo della PR (obbligatorio)
   - **Description**: aggiungi una descrizione opzionale
   - Clicca **OK**

4. **Risultato:**
   - Vedrai una notifica di successo con il numero della PR
   - Puoi cliccare "Open in Browser" per aprire la PR su Azure DevOps

### Screenshot Esempio

```
┌──────────────────────────────────────────────┐
│ Create Azure DevOps Pull Request            │
├──────────────────────────────────────────────┤
│ Source Branch: │ feature/new-feature      ▼  │
│ Target Branch: │ main                     ▼  │
│ ─────────────────────────────────────────────│
│ Title: │ Add new feature                  │  │
│ Description:                                 │
│ ┌──────────────────────────────────────────┐ │
│ │ This PR adds the new feature for...     │ │
│ │                                          │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│              [Cancel]  [  OK  ]              │
└──────────────────────────────────────────────┘
```

> 💡 **Nota**: Il plugin appare solo se il repository è clonato da Azure DevOps!

## 🏗️ Architettura

### Struttura del progetto

```
src/main/kotlin/paol0b/azuredevops/
├── actions/
│   └── CreatePullRequestAction.kt      # Action per creare PR
├── model/
│   └── AzureDevOpsModels.kt           # Data classes
├── services/
│   ├── AzureDevOpsApiClient.kt        # Client REST API
│   ├── AzureDevOpsConfigService.kt    # Gestione configurazione
│   └── GitRepositoryService.kt        # Servizio Git locale
└── ui/
    ├── AzureDevOpsConfigurable.kt     # UI Settings
    └── CreatePullRequestDialog.kt     # Dialog creazione PR
```

### Componenti principali

#### 1. **AzureDevOpsRepositoryDetector**
- Rileva automaticamente se il repository è di Azure DevOps
- Supporta URL HTTPS e SSH (formati dev.azure.com e visualstudio.com)
- Estrae Organization, Project e Repository dall'URL remoto Git
- Pattern matching per tutti i formati Azure DevOps conosciuti

#### 2. **AzureDevOpsConfigService**
- Gestisce solo il Personal Access Token (PAT)
- Usa rilevamento automatico per organization, project, repository
- Salva il PAT in modo sicuro usando `PasswordSafe`
- Persistenza con `PersistentStateComponent`

#### 3. **AzureDevOpsApiClient**
- Comunica con Azure DevOps REST API v7.0
- Gestisce autenticazione Basic Auth
- Error handling robusto

#### 4. **GitRepositoryService**
- Interagisce con Git4Idea plugin
- Rileva branch locali e remoti
- Determina automaticamente il target branch (main/master)

#### 5. **CreatePullRequestAction**
- Action registrata nel menu VCS
- Mostra il dialog e gestisce la creazione
- Task in background per non bloccare l'UI
- Notifiche di successo/errore

## 🔧 Sviluppo

### Setup ambiente di sviluppo

```bash
# Clona il repository
git clone https://github.com/paol0b/azuredevops-plugin.git
cd azuredevops-plugin

# Build
./gradlew buildPlugin

# Run in IDE sandbox
./gradlew runIde

# Test
./gradlew test
```

### Dipendenze

- **Kotlin**: 2.1.0
- **IntelliJ Platform**: 2025.1
- **Gson**: 2.11.0 (per JSON parsing)
- **Git4Idea**: Plugin bundled

### API Azure DevOps utilizzate

- **POST** `/git/repositories/{repositoryId}/pullrequests` - Crea PR
- **GET** `/git/repositories/{repositoryId}` - Verifica connessione

Documentazione: [Azure DevOps REST API Reference](https://learn.microsoft.com/en-us/rest/api/azure/devops/)

## 🐛 Troubleshooting

### "Questo non è un repository Azure DevOps"
- Verifica che il repository sia clonato da Azure DevOps
- Controlla l'URL remoto: `git remote -v`
- L'URL deve corrispondere a uno dei formati supportati:
  - `https://dev.azure.com/{org}/{project}/_git/{repo}`
  - `git@ssh.dev.azure.com:v3/{org}/{project}/{repo}`

### "Autenticazione fallita (401)"
- Verifica che il PAT sia corretto
- Controlla che il PAT non sia scaduto
- Assicurati che il PAT abbia i permessi corretti

### "Risorsa non trovata (404)"
- Il repository potrebbe non esistere o il nome è errato
- Verifica su Azure DevOps che il repository esista
- Assicurati di avere accesso al repository
- Controlla che l'URL remoto Git sia corretto

### "Permessi insufficienti (403)"
- Il PAT deve avere permessi:
  - Code: Read & Write
  - Pull Request: Read & Write

### "Nessun repository Git trovato"
- Assicurati che il progetto abbia un repository Git inizializzato
- Verifica che Git4Idea plugin sia abilitato
- Controlla che ci sia almeno un remote configurato: `git remote -v`

### "Il plugin non appare nel menu VCS"
- Verifica che il repository sia clonato da Azure DevOps
- L'action appare solo per repository Azure DevOps rilevati automaticamente
- Controlla l'URL del remoto Git

### "Il branch di origine e destinazione non possono essere uguali"
- Seleziona branch diversi per source e target
- Tipicamente: feature branch → main/master

## 📄 Licenza

Questo progetto è rilasciato sotto licenza MIT.

## 👤 Autore

**Paolo Bertinetti**
- GitHub: [@paol0b](https://github.com/paol0b)

## 🤝 Contribuire

I contributi sono benvenuti! 

1. Fork del progetto
2. Crea un branch per la feature (`git checkout -b feature/AmazingFeature`)
3. Commit delle modifiche (`git commit -m 'Add some AmazingFeature'`)
4. Push al branch (`git push origin feature/AmazingFeature`)
5. Apri una Pull Request

## 🗺️ Roadmap

### MVP (Implementato ✅)
- [x] Configurazione Azure DevOps
- [x] Creazione Pull Request
- [x] Selezione branch source/target
- [x] Rilevamento automatico main/master
- [x] Salvataggio sicuro credenziali
- [x] Notifiche successo/errore
- [x] Link alla PR creata

### Future Features (Pianificate)
- [ ] Visualizzare lista PR attive
- [ ] Approvare/Rifiutare PR dall'IDE
- [ ] Aggiungere commenti alle PR
- [ ] Diff viewer integrato
- [ ] Assegnare reviewer
- [ ] Gestire work items collegati
- [ ] Supporto multi-repository
- [ ] Template per descrizioni PR
- [ ] Draft PR support
- [ ] Auto-complete options

## ❓ FAQ

**Q: Supporta GitHub?**  
A: No, questo plugin è specifico per Azure DevOps. Per GitHub usa il plugin ufficiale.

**Q: Funziona con GitLab/Bitbucket?**  
A: No, solo Azure DevOps.

**Q: Quali IDE sono supportati?**  
A: Tutti gli IDE basati su IntelliJ Platform (2025.1+): IntelliJ IDEA, Rider, PyCharm, WebStorm, ecc.

**Q: Il PAT è sicuro?**  
A: Sì, viene salvato usando PasswordSafe dell'IDE, che cripta le credenziali.

**Q: Posso usarlo in progetti multi-repository?**  
A: Attualmente supporta il primo repository trovato. Support multi-repo è nella roadmap.

---

**Buon sviluppo! 🚀**
