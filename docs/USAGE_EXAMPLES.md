# Esempi di Utilizzo

Questa guida mostra esempi pratici di come usare il plugin Azure DevOps Integration.

## 📚 Indice

1. [Configurazione Iniziale](#configurazione-iniziale)
2. [Creare la Prima PR](#creare-la-prima-pr)
3. [Scenari Comuni](#scenari-comuni)
4. [Tips & Tricks](#tips--tricks)

---

## Configurazione Iniziale

### Scenario: Primo utilizzo del plugin

**Situazione**: Hai appena installato il plugin e vuoi configurarlo.

**Passi**:

1. **Ottieni le informazioni Azure DevOps**
   ```
   URL del tuo progetto: https://dev.azure.com/mycompany/MyProject/_git/my-repo
   
   Da questo URL estrai:
   - Organization: mycompany
   - Project: MyProject  
   - Repository: my-repo
   ```

2. **Crea il PAT** (vedi [PAT_SETUP.md](PAT_SETUP.md))

3. **Configura il plugin**
   - Apri Settings (Ctrl+Alt+S / Cmd+,)
   - Tools → Azure DevOps
   - Compila:
     ```
     Organization: mycompany
     Project: MyProject
     Repository: my-repo
     PAT: [il tuo token]
     ```
   - Clicca "Test Connection"
   - Se OK → Apply → OK

---

## Creare la Prima PR

### Scenario: Feature branch verso main

**Situazione**: Hai completato una feature e vuoi creare una PR.

**Branch corrente**: `feature/add-login`  
**Target**: `main`

**Passi**:

1. **Assicurati di aver pushato le modifiche**
   ```bash
   git push origin feature/add-login
   ```

2. **Apri il dialog di creazione PR**
   - Menu: VCS → Create Azure DevOps PR
   - Oppure usa lo shortcut (se configurato)

3. **Compila il form**
   ```
   Source Branch: feature/add-login (già selezionato)
   Target Branch: main (già selezionato automaticamente)
   Title: Add user login functionality
   Description:
   ## Changes
   - Added login form
   - Implemented authentication logic
   - Added unit tests
   
   ## Testing
   - Tested on Chrome, Firefox, Safari
   - All tests passing
   ```

4. **Clicca OK**

5. **Risultato**
   - Notifica: "Pull Request Created Successfully"
   - Click "Open in Browser" per vedere la PR

---

## Scenari Comuni

### 1. Bugfix urgente

**Situazione**: Bug critico in produzione

```
Current Branch: hotfix/critical-bug
Target: main
```

**Dialog**:
```
Source: hotfix/critical-bug
Target: main
Title: [HOTFIX] Fix critical null pointer exception
Description:
## Bug
- NullPointerException in UserService.getUser()

## Fix
- Added null check before accessing user object
- Added defensive programming

## Impact
- Critical: affects all users
- Should be merged ASAP

Fixes: #1234
```

### 2. Release branch

**Situazione**: Preparare una release

```
Current Branch: release/v2.0.0
Target: main
```

**Dialog**:
```
Source: release/v2.0.0
Target: main
Title: Release v2.0.0
Description:
## Release Notes

### New Features
- Feature A
- Feature B

### Bug Fixes
- Fixed issue #123
- Fixed issue #456

### Breaking Changes
- API endpoint /old removed

## Checklist
- [x] All tests passing
- [x] Documentation updated
- [x] Changelog updated
- [x] Version bumped
```

### 3. Multiple piccole modifiche

**Situazione**: Piccoli fix/refactoring

```
Current Branch: chore/code-cleanup
Target: develop
```

**Dialog**:
```
Source: chore/code-cleanup
Target: develop
Title: Code cleanup and refactoring
Description:
## Changes
- Removed unused imports
- Fixed typos in comments
- Improved variable naming
- Extracted magic numbers to constants

## Type
Chore/Refactoring - no functional changes
```

### 4. PR da branch remoto

**Situazione**: Vuoi creare PR per un branch che hai fetchato

```
Fetched: origin/feature/new-api
```

**Passi**:
1. Il plugin rileva automaticamente anche i branch remoti
2. Seleziona il branch remoto dal dropdown
3. Procedi normalmente

**Dialog**:
```
Source: feature/new-api
Target: main
Title: Implement new REST API endpoints
Description: ...
```

### 5. PR verso branch non-standard

**Situazione**: Target non è main/master

```
Current Branch: feature/experimental
Target: develop
```

**Dialog**:
```
Source: feature/experimental
Target: develop (seleziona manualmente)
Title: Add experimental feature
Description: ...
```

---

## Tips & Tricks

### 🎯 Template per descrizioni

Crea template standardizzati per le tue PR:

#### Template Bug Fix
```markdown
## 🐛 Bug Description
[Descrivi il bug]

## 🔧 Fix
[Come hai risolto]

## ✅ Testing
- [ ] Unit tests added
- [ ] Manual testing done
- [ ] Regression testing done

## 📎 Related
Fixes: #[issue-number]
```

#### Template Feature
```markdown
## ✨ Feature
[Descrivi la feature]

## 💡 Implementation
[Dettagli implementazione]

## 🧪 Testing
[Come è stata testata]

## 📖 Documentation
- [ ] Code comments added
- [ ] README updated
- [ ] API docs updated

## 🔗 Related
Closes: #[issue-number]
```

#### Template Refactoring
```markdown
## ♻️ Refactoring
[Cosa hai refactorizzato]

## 🎯 Goals
- Improve code quality
- Reduce complexity
- Better maintainability

## ⚠️ Breaking Changes
[Se ci sono breaking changes]

## ✅ Verification
- [ ] All tests passing
- [ ] No functional changes
```

### ⌨️ Keyboard Shortcuts

Puoi configurare uno shortcut personalizzato:

1. Settings → Keymap
2. Cerca "Create Azure DevOps PR"
3. Click destro → Add Keyboard Shortcut
4. Esempio: `Ctrl+Shift+P` (Windows/Linux) o `Cmd+Shift+P` (Mac)

### 📋 Checklist pre-PR

Prima di creare una PR, verifica:

- [ ] Codice compilato senza errori
- [ ] Test passano tutti
- [ ] Codice formattato correttamente
- [ ] Commenti e documentazione aggiornati
- [ ] Commit message descrittivi
- [ ] Branch aggiornato con target (rebase/merge)
- [ ] Nessun file sensibile committato
- [ ] Changelog aggiornato (se applicabile)

### 🔍 Verifica prima di pushare

```bash
# Verifica lo stato
git status

# Verifica i commit
git log origin/main..HEAD

# Verifica le differenze
git diff origin/main

# Se tutto OK
git push origin [branch-name]
```

### 🚀 Workflow efficiente

**Flusso consigliato**:

1. Crea branch feature
   ```bash
   git checkout -b feature/my-feature
   ```

2. Lavora e committa
   ```bash
   git add .
   git commit -m "feat: add my feature"
   ```

3. Push del branch
   ```bash
   git push -u origin feature/my-feature
   ```

4. **Usa il plugin per creare PR** ⚡
   - VCS → Create Azure DevOps PR
   - Compila e invia

5. Review e merge su Azure DevOps

### 🎨 Markdown nella descrizione

Puoi usare Markdown nella descrizione:

```markdown
# Titolo H1
## Titolo H2

**Bold text**
*Italic text*

- Lista
- Elementi

1. Lista
2. Numerata

`code inline`

```python
# Code block
def hello():
    print("Hello")
```

| Colonna 1 | Colonna 2 |
|-----------|-----------|
| Data 1    | Data 2    |

> Quote

[Link](https://example.com)

![Image](url)
```

### 🔗 Riferimenti automatici

Azure DevOps supporta riferimenti automatici:

- `#123` → Work item 123
- `!456` → PR 456  
- `@username` → Menziona utente
- `Fixes #123` → Collega e chiude work item al merge
- `Related to #456` → Collega work item

Esempio:
```
Title: Fix login bug

Description:
This PR fixes the login issue.

Fixes #1234
Related to #1235, #1236
CC: @john.doe @jane.smith
```

### 📊 Branch naming conventions

Usa convenzioni standard:

```
feature/     → Nuove feature
bugfix/      → Bug fix
hotfix/      → Fix critici
release/     → Preparazione release
chore/       → Task manutenzione
refactor/    → Refactoring
docs/        → Solo documentazione
test/        → Solo test
```

Esempi:
- `feature/user-authentication`
- `bugfix/fix-null-pointer`
- `hotfix/critical-security-patch`
- `release/v2.0.0`
- `chore/update-dependencies`

### 🎯 Commit message conventions

Usa [Conventional Commits](https://www.conventionalcommits.org/):

```
feat:     → Nuova feature
fix:      → Bug fix
docs:     → Documentazione
style:    → Formatting
refactor: → Refactoring
test:     → Test
chore:    → Manutenzione
```

Esempi:
```
feat: add user login functionality
fix: resolve null pointer exception in UserService
docs: update API documentation
refactor: extract common logic to helper class
```

---

## 🆘 Problemi Comuni

### PR già esistente

**Errore**: "A pull request already exists for these branches"

**Soluzione**: 
- Verifica su Azure DevOps se esiste già una PR
- Se sì, aggiorna quella esistente invece di crearne una nuova
- Se no, controlla che i branch siano corretti

### Branch non aggiornato

**Problema**: Target branch è avanti rispetto al tuo branch

**Soluzione**:
```bash
# Aggiorna il target branch
git checkout main
git pull

# Torna al tuo branch
git checkout feature/my-feature

# Rebase o merge
git rebase main  # oppure git merge main

# Push (forzato se hai fatto rebase)
git push --force-with-lease
```

### Conflitti

**Problema**: Il tuo branch ha conflitti con il target

**Soluzione**:
1. Risolvi i conflitti localmente (vedi sopra)
2. Poi crea la PR
3. Oppure crea la PR e risolvi i conflitti su Azure DevOps

---

**Hai altri scenari o domande?** Contribuisci con esempi su [GitHub](https://github.com/paol0b/azuredevops-plugin)!
