# Come ottenere un Personal Access Token (PAT) per Azure DevOps

Questa guida ti mostra come creare un Personal Access Token su Azure DevOps da usare con il plugin.

## 📋 Prerequisiti

- Account Azure DevOps attivo
- Accesso al progetto e repository per cui vuoi creare PR
- Permessi sufficienti per creare token (di solito tutti gli utenti possono farlo)

## 🔑 Passi per creare il PAT

### 1. Accedi ad Azure DevOps

Vai su [https://dev.azure.com](https://dev.azure.com) e accedi con il tuo account.

### 2. Apri le User Settings

1. Clicca sulla tua **icona profilo** in alto a destra
2. Seleziona **Personal access tokens**

![User Settings](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/media/use-personal-access-tokens-to-authenticate/select-personal-access-tokens.png)

### 3. Crea un nuovo token

1. Clicca sul pulsante **+ New Token**
2. Compila i campi:

#### Nome
```
Nome descrittivo, es: "JetBrains IDE Plugin"
```

#### Organization
```
Seleziona la tua organization (o "All accessible organizations" se vuoi usarlo ovunque)
```

#### Expiration
```
Scegli una data di scadenza (raccomandato: 90 giorni)
Importante: segna la data e rinnova il token prima della scadenza!
```

#### Scopes (Permessi)

**⚠️ IMPORTANTE**: Devi selezionare i permessi corretti!

Clicca su **"Show all scopes"** in basso, poi seleziona:

✅ **Code**
- ☑️ Read
- ☑️ Write

✅ **Pull Request Threads**
- ☑️ Read & write

Oppure, in alternativa, puoi usare:

✅ **Code**
- ☑️ Full

Screenshot esempio permessi:
```
Scopes:
  Code
    ☑ Read
    ☑ Write
  
  Pull Request Threads
    ☑ Read & write
```

### 4. Genera e copia il token

1. Clicca su **Create**
2. **‼️ IMPORTANTE**: Copia immediatamente il token generato!
   ```
   Il token verrà mostrato UNA SOLA VOLTA
   Non potrai più visualizzarlo dopo aver chiuso la finestra
   ```
3. Salva il token in un posto sicuro (es: password manager)

Il token avrà un formato simile a:
```
abcdefghijklmnopqrstuvwxyz1234567890abcdefghijklmnopqr
```

### 5. Usa il token nel plugin

1. Apri il tuo IDE JetBrains
2. Vai su **Settings → Tools → Azure DevOps**
3. Incolla il token nel campo **Personal Access Token (PAT)**
4. Compila gli altri campi (Organization, Project, Repository)
5. Clicca **Test Connection** per verificare
6. Clicca **Apply** → **OK**

## 🔒 Sicurezza del token

### Best Practices

✅ **DO:**
- Usa date di scadenza ragionevoli (30-90 giorni)
- Crea token separati per scopi diversi
- Rinnova i token prima della scadenza
- Salva i token in modo sicuro (password manager)
- Revoca immediatamente token compromessi

❌ **DON'T:**
- Non condividere mai il token con altri
- Non committare il token nel codice sorgente
- Non usare token senza scadenza
- Non dare più permessi del necessario
- Non riutilizzare lo stesso token per molti servizi

### Revoca di un token compromesso

Se pensi che il tuo token sia stato compromesso:

1. Vai su **User Settings → Personal access tokens**
2. Trova il token nella lista
3. Clicca sui **tre puntini** (⋯) → **Revoke**
4. Crea un nuovo token con permessi minimi necessari
5. Aggiorna la configurazione nel plugin

## 🔄 Rinnovo del token

I token scadono! Per rinnovare:

### Metodo 1: Rinnova token esistente
1. Vai su **User Settings → Personal access tokens**
2. Trova il token da rinnovare
3. Clicca sui **tre puntini** (⋯) → **Regenerate**
4. Copia il nuovo token
5. Aggiorna nel plugin

### Metodo 2: Crea nuovo token
1. Revoca il vecchio token
2. Crea un nuovo token seguendo i passi sopra
3. Aggiorna nel plugin

## 🐛 Troubleshooting

### "Autenticazione fallita (401)"

**Causa**: Token non valido o scaduto

**Soluzione**:
1. Verifica di aver copiato il token completo (senza spazi)
2. Controlla la data di scadenza del token
3. Crea un nuovo token se quello attuale è scaduto
4. Verifica di essere loggato nella organization corretta

### "Permessi insufficienti (403)"

**Causa**: Il token non ha i permessi necessari

**Soluzione**:
1. Vai su **Personal access tokens**
2. Clicca su **tre puntini** → **Edit**
3. Verifica che siano selezionati:
   - Code: Read & Write
   - Pull Request Threads: Read & write
4. Salva e rigenera il token se necessario

### "Risorsa non trovata (404)"

**Causa**: Organization, Project o Repository non corretto

**Soluzione**:
1. Verifica il nome della Organization nell'URL di Azure DevOps
   ```
   https://dev.azure.com/{organization}
   ```
2. Verifica il nome del Project e Repository
3. Assicurati di avere accesso al progetto
4. Controlla che il token abbia scope sulla organization corretta

## 📚 Riferimenti

- [Documentazione ufficiale Azure DevOps PAT](https://learn.microsoft.com/en-us/azure/devops/organizations/accounts/use-personal-access-tokens-to-authenticate)
- [Gestione sicurezza e permessi](https://learn.microsoft.com/en-us/azure/devops/organizations/security/about-permissions)
- [Azure DevOps REST API](https://learn.microsoft.com/en-us/rest/api/azure/devops/)

## 💡 Tips

### Organizzazione dei token

Se hai molti progetti, considera di organizzare i token così:

```
Nome Token              | Scope           | Scadenza    | Uso
------------------------|-----------------|-------------|------------------
IDE-ProjectA           | Code, PR        | 90 giorni   | Plugin IDE
IDE-ProjectB           | Code, PR        | 90 giorni   | Plugin IDE
CI-Pipeline-ProjectA   | Build, Release  | 1 anno      | Azure Pipelines
```

### Promemoria scadenza

Imposta un promemoria calendario 1 settimana prima della scadenza del token per rinnovarlo in tempo!

### Rotazione token

Per sicurezza massima, rinnova i token ogni 30-60 giorni anche se non sono scaduti.

---

**Hai domande?** Apri un issue su [GitHub](https://github.com/paol0b/azuredevops-plugin/issues)
