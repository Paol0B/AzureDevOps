# Authentication Setup - Azure DevOps Integration

This plugin uses **OAuth 2.0** for secure authentication with your Microsoft account. No need to create tokens or manage credentials manually!

---

## How It Works

The plugin authenticates using **OAuth 2.0**, which means:
- ✅ You sign in with your Microsoft account in your browser
- ✅ Your credentials are never stored locally
- ✅ The plugin only gets a token to access your Azure DevOps
- ✅ The token automatically refreshes when it expires
- ✅ It's as secure as signing into websites

---

## Sign In (Quick Version)

### The Easiest Way - Clone a Repository

1. Go to **File** → **New** → **Project from Version Control**
2. Click **Azure DevOps**
3. A login prompt appears (if not already logged in)
4. Click **"Sign in with Browser"**
5. Your browser opens automatically
6. Sign in with your **Microsoft account**
7. Grant the plugin access
8. Return to the IDE - you're logged in!
9. The clone tool window shows your repositories
10. Select and clone any repository

**Or add an account manually:**

1. Go to **Settings** → **Tools** → **Azure DevOps Accounts**
2. Click **Add**
3. Enter your organization URL: `https://dev.azure.com/yourorganization`
4. Click **"Sign in with Browser"**
5. A browser window opens
6. Sign in with your Microsoft account
7. Click to approve access
8. Done! The plugin remembers your account

That's it. You don't need to do anything else. The plugin handles all the technical details.

---

## FAQ - Authentication

### Q: Is my password safe?
**A:** Yes. You never enter your password in the IDE. You sign in using your browser, which is much more secure.

### Q: Do I need to manually refresh tokens?
**A:** No. The plugin automatically refreshes tokens when they expire. You won't notice anything.

### Q: Can I use multiple accounts?
**A:** Yes. Go to **Settings** → **Tools** → **Azure DevOps Accounts** and click **Add** again.

### Q: What if I forget to sign in?
**A:** The plugin will tell you when you open a PR tool window. Just click "Add Account" and follow the steps.

### Q: Can I sign out?
**A:** Yes. In **Settings** → **Tools** → **Azure DevOps Accounts**, click the account and select **Remove**.

### Q: What does "Valid", "Expired", or "Invalid" mean?
- **Valid** ✅ - Your account is working
- **Expired** ⚠️ - Token expired but will refresh automatically next time you use it
- **Invalid** ❌ - Something went wrong, try signing in again

---

## Troubleshooting

### **"Browser didn't open"**
- Your browser should open automatically
- If it doesn't, manually visit the URL shown in the dialog
- Or check if a pop-up blocker is preventing it

### **"Authentication failed"**
- Make sure you're signing in with the right Microsoft account
- Check that your account has access to the Azure DevOps organization
- Try removing the account and adding it again

### **"Account shows as Invalid"**
1. Go to **Settings** → **Tools** → **Azure DevOps Accounts**
2. Click **Remove** on the invalid account
3. Click **Add** and sign in again
4. The account should now show as **Valid**

### **"Token expired" warning**
- This is normal. The plugin automatically refreshes the token
- You'll see the warning once, then it refreshes in the background
- No action needed from you

### **"Organization URL is wrong"**
- Check your URL format: `https://dev.azure.com/yourorganization`
- Replace `yourorganization` with your actual organization name
- Don't include trailing slashes

---

## Security & Privacy

### What data does the plugin collect?
- **Only tokens** to access Azure DevOps API
- **Your organization URL** to know which Azure DevOps to connect to
- Nothing else. No tracking, no analytics, no personal data.

### Where are tokens stored?
- In your IDE's **PasswordSafe** (encrypted password storage)
- The same place where other IDE secrets are stored
- Your operating system's security manages the encryption

### Can I see the token?
- No, it's hidden for security
- The plugin shows you only the account status and organization name

---

## What If You Have Problems?

1. **Check your internet connection** - OAuth requires a browser request
2. **Clear browser cookies** - Sometimes old cookies cause issues
3. **Try a different browser** - Switch from Chrome to Firefox to test
4. **Restart the IDE** - A full restart often fixes auth issues
5. **Remove and re-add the account** - Complete re-authentication

Still stuck? Visit [GitHub Issues](https://github.com/paol0b/AzureDevOps/issues)

---

## Next Steps

Once authenticated, you can:
- ✅ [View pull requests](GETTING_STARTED.md)
- ✅ [Create new PRs](USAGE_EXAMPLES.md)
- ✅ [Review code changes](USAGE_EXAMPLES.md)
- ✅ [Manage comments](USAGE_EXAMPLES.md)
