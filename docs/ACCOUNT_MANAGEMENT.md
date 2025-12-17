# Account Management & Token Refresh

## Overview

Il plugin ora supporta la gestione avanzata degli account con refresh token automatico e interfaccia dedicata per monitorare lo stato dell'autenticazione.

## Nuove Funzionalità

### 1. Gestione Account Globale

**Location:** `Settings → Tools → Azure DevOps Accounts`

- **Visualizzazione account**: Tabella con tutti gli account configurati
- **Stato autenticazione**: Visualizzazione in tempo reale dello stato (Valid ✓ / Expired ⚠ / Revoked ✗)
- **Data di scadenza**: Mostra quando il token scadrà
- **Azioni disponibili**:
  - ➕ **Add**: Aggiunge un nuovo account tramite OAuth
  - ➖ **Remove**: Rimuove un account e le sue credenziali
  - 🔄 **Refresh Token**: Rinnova il token usando il refresh token
  - ▶️ **Re-login**: Riautentica completamente l'account

### 2. Configurazione Progetto Semplificata

**Location:** `Settings → Tools → Azure DevOps` (a livello progetto)

- **Selezione account**: Dropdown per scegliere quale account usare
- **Auto-detection**: Rileva automaticamente l'organizzazione dal repository Git
- **Stato token**: Mostra lo stato dell'account selezionato
- **Test connessione**: Verifica che l'autenticazione funzioni
- **Link a gestione globale**: Pulsante per aprire la gestione account

### 3. Refresh Token Automatico

Il sistema gestisce automaticamente il refresh dei token:

- **Microsoft Entra ID**: Usa l'endpoint OAuth 2.0 standard per il refresh
- **Trigger automatico**: Quando un token è scaduto, viene automaticamente rinnovato
- **Fallback**: Se il refresh fallisce, l'utente può riautenticarsi
- **Scopes**: Include `offline_access` per ottenere refresh token duratura

### 4. Tracking Scadenza Token

- **Timestamp expiresAt**: Salva quando il token scadrà
- **Timestamp lastRefreshed**: Traccia l'ultimo refresh
- **Validazione automatica**: Controlla lo stato prima di ogni operazione

## Architettura

### AccountManager

```kotlin
data class AccountData(
    var id: String,
    var serverUrl: String,
    var displayName: String,
    var expiresAt: Long,      // Unix timestamp - scadenza token
    var lastRefreshed: Long    // Unix timestamp - ultimo refresh
)

enum class AccountAuthState {
    VALID,       // Token valido e non scaduto
    EXPIRED,     // Token scaduto (può essere refreshato)
    REVOKED,     // Token revocato o invalido
    UNKNOWN      // Stato sconosciuto
}
```

### OAuthService

Nuovo metodo per refresh token secondo [Microsoft docs](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-device-code):

```kotlin
fun refreshAccessToken(refreshToken: String, organizationUrl: String): OAuthResult?
```

**Request:**
```
POST https://login.microsoftonline.com/organizations/oauth2/v2.0/token
Content-Type: application/x-www-form-urlencoded

client_id={CLIENT_ID}
&grant_type=refresh_token
&refresh_token={REFRESH_TOKEN}
&scope={SCOPES}
```

**Response:**
```json
{
  "access_token": "new_access_token",
  "refresh_token": "new_refresh_token",  // Può essere diverso
  "expires_in": 3600
}
```

### ConfigService

Priorità nell'ottenimento del token:

1. **Account OAuth globale** (con refresh automatico se scaduto)
2. **PAT a livello progetto** (PasswordSafe)
3. **Git Credential Helper** (fallback)

## Flussi di Utilizzo

### Primo Login

1. User apre `Settings → Tools → Azure DevOps Accounts`
2. Click su "Add" (➕)
3. Inserisce URL organizzazione
4. Click su "Sign in with Browser"
5. Si autentica nel browser
6. Token e refresh token salvati automaticamente

### Token Scaduto - Refresh Automatico

1. Plugin rileva token scaduto durante un'operazione
2. Controlla se esiste un refresh token
3. Chiama automaticamente l'endpoint di refresh
4. Aggiorna i token salvati
5. Riprende l'operazione

### Token Scaduto - Refresh Manuale

1. User apre `Settings → Tools → Azure DevOps Accounts`
2. Vede lo stato "⚠ Expired" per l'account
3. Seleziona l'account
4. Click su "Refresh Token" (🔄)
5. Token rinnovato automaticamente

### Token Revocato - Re-autenticazione

1. User apre `Settings → Tools → Azure DevOps Accounts`
2. Vede lo stato "✗ Revoked" per l'account
3. Seleziona l'account
4. Click su "Re-login" (▶️)
5. Viene rimosso il vecchio account e aperto il dialog di login
6. Nuovo token e refresh token salvati

## Storage Sicuro

Tutti i token sono salvati in modo sicuro usando IntelliJ's PasswordSafe:

- **Access Token**: `AzureDevOps:{accountId}`
- **Refresh Token**: `AzureDevOps:{accountId}-refresh`
- **Metadata**: XML file in `~/.config/JetBrains/.../azureDevOpsAccounts.xml`

## Compatibilità

Il sistema è **backward compatible**:

- Progetti esistenti continuano a funzionare con PAT a livello progetto
- Account OAuth hanno precedenza su PAT quando disponibili
- È possibile usare entrambi i sistemi contemporaneamente

## Documentazione Microsoft

Implementazione basata su:

- [Device Code Flow](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-device-code)
- [Refresh Tokens](https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-auth-code-flow#refresh-the-access-token)
- [Token Lifetimes](https://learn.microsoft.com/en-us/azure/active-directory/develop/active-directory-configurable-token-lifetimes)

## Scopes Utilizzati

```
499b84ac-1321-427f-aa17-267ca6975798/.default offline_access
```

- `499b84ac-1321-427f-aa17-267ca6975798`: Azure DevOps Resource ID
- `.default`: Tutti i permessi concessi all'app
- `offline_access`: Richiede refresh token per sessioni persistenti
