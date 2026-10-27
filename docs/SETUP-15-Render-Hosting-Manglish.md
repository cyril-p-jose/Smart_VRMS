# SETUP-15 — Render Free API hosting (Manglish)

Purpose: downloaded desktop app-inu laptop off aayalum connect cheyyan pattunna public HTTPS Java API create cheyyuka. Ithu current connected-records stage aanu; full rental/finance scope complete aayi ennu artham illa.

Prerequisites: ningalude Render account/project, GitHub repo access, existing Supabase project, SETUP-07 server-role password privately retained, SETUP-14 live staff check PASS. Local software install venda. **Free plan mathram**; paid plan/payment method add cheyyenda avashyam illa. Limits exceed aayal service pause/fail aakaam. Free service idle 15 minutes kazhinjal sleep cheyyum; first request wake aavan approximately one minute edukkam.

Official references: [Render free services](https://render.com/docs/free), [Docker services](https://render.com/docs/docker), [deployments](https://render.com/docs/deploys), [environment variables](https://render.com/docs/configure-environment-variables), [health checks](https://render.com/docs/health-checks).

## Numbered steps

1. Ningal thanna [Render project](https://dashboard.render.com/project/prj-dbf4om67bikc73c69jo0) open cheyyuka. Correct account aanu ennu profile/workspace nokki confirm cheyyuka. Codex connector old unrelated account aanu; athiloode njan service create cheythittilla.
2. Project-il **New → Web Service** select cheyyuka. **Existing Image** / Static Site / Postgres select cheyyaruthu.
3. GitHub repository **cyril-p-jose/Smart_VRMS** connect cheyyuka. GitHub connection avoid cheyyan **Public Git Repository** option undenkil `https://github.com/cyril-p-jose/Smart_VRMS.git` use cheyyam; ee option-il future deploys manual aayirikkum.
4. Settings:

| Setting | Value |
|---|---|
| Name | smart-vrms-api (already taken aanenkil unique suffix add cheyyuka) |
| Branch | main |
| Region | Singapore |
| Language / Runtime | Docker |
| Root Directory | blank |
| Dockerfile Path | ./Dockerfile |
| Docker Build Context | . |
| Docker Command / Start override | blank — Dockerfile entrypoint use cheyyanam |
| Instance Type | Free |
| Health Check Path | /health |

5. **Environment Variables** section-il iva add cheyyuka. Secret value fields-il mathram password enter cheyyuka; chat/screenshot/log-il venda:

| Key | Value |
|---|---|
| SUPABASE_URL | https://wvtnepxynjjbazczkukh.supabase.co |
| SUPABASE_PUBLISHABLE_KEY | Existing publishable key value; local file-il ninnu copy cheyyuka (below) |
| SMART_VRMS_DB_HOST | aws-0-ap-northeast-1.pooler.supabase.com |
| SMART_VRMS_DB_PORT | 5432 |
| SMART_VRMS_DB_USER | smart_vrms_api.wvtnepxynjjbazczkukh |
| SMART_VRMS_DB_PASSWORD | SETUP-07-il create cheytha **server-role database password** |
| SMART_VRMS_BEHIND_TLS_PROXY | true |

Public key existing file open cheyyan PowerShell:

```powershell
Set-Location 'C:\Users\HP\Desktop\smart-VRMS'
notepad '.\local-config\supabase-public.properties'
```

`supabase.publishableKey=` line-il equals sign-inu shesham ulla value mathram Render-il copy cheyyuka. Key veendum chat-il ayakkenda. Database password **postgres owner password-o staff sign-in password-o alla**. CA certificate server image-il bundled public certificate aanu; separate secret file upload venda. PORT Render supply cheyyum; manual aayi override cheyyenda.

6. GitHub connected aanenkil **Auto-Deploy → After CI Checks Pass** select cheyyuka, option available aanenkil. Initial creation-il **Off** select cheythu manual deploy cheyyunnathum okay. Public repo URL vazhi aanenkil manual deploy aanu.
7. **Deploy Web Service** click cheyyuka. Build complete aayi status **Live** aakunnathu vare wait cheyyuka. Error vannal raw environment dump/password screenshot ayakkaruthu; sanitized error line mathram share cheyyuka. Paid plan choose cheythu fix cheyyaruthu.
8. Service Settings-il /health set cheyyan initial screen-il option kandillenkil ippol set cheyyuka. Public service URL copy cheyyuka. Athinte end-il `/health` add cheythu browser-il open cheyyuka. Expected: HTTP 200 JSON health response. First idle request slow aayekkam; once wait cheyyuka. Health response liveness mathram aanu; staff save acceptance separate test aanu.
9. Ithu safe reply aayi ayakkuka:

```text
DONE SETUP-15
Service name: ...
Public HTTPS URL: https://...onrender.com
Plan: Free
Status: Live / sanitized error
/health: JSON response / error
```

## Troubleshooting

- Dockerfile not found: branch main, root blank, path ./Dockerfile, context . check cheyyuka. Source commit/CI ready aayathinu shesham deploy cheyyuka.
- Restricted database connection failure: DB_USER full project suffix-ode aanu ennu nokkuka; DB_PASSWORD server-role password aanu. TLS verification disable cheyyaruthu; owner postgres login use cheyyaruthu.
- Config missing: key names exact copy cheyyuka. Public key publishable aanu; secret/service-role key ithinu substitute cheyyaruthu.
- No open port: SMART_VRMS_BEHIND_TLS_PROXY=true ensure cheyyuka; Docker Command override blank aakkanam.
- Build fails: same build veendum repeatedly run cheyyathe sanitized first error share cheyyuka.
- Free option unavailable/card required: stop and report. Billing/payment authorize cheythittilla.

HTTPS URL kittiyal njan athu verify cheythu client default endpoint-il include cheyyum; athinu shesham downloaded app valid staff account vechu test cheyyam. Existing preview download ithuvare change cheythittilla.
