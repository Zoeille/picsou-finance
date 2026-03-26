# Picsou 💰

> Tableau de bord de finances personnelles — suivi d'enveloppes, objectifs, synchronisation bancaire.

Self-hosted, open-source, single-user. Construit avec Spring Boot + React.

---

## Statut du projet

> **Pré-release publique** — L'application est partagée en l'état pour permettre à la communauté de contribuer. Elle est fonctionnelle pour un usage personnel mais manque encore de polish et de couverture de tests. Elle s'améliorera avec le temps et les contributions de chacun. N'hésitez pas à ouvrir des issues ou des pull requests !
> Le projet a été complétement vibecodé sur le frontend et en partie sur le back-end, c'est un projet perso sans prise de tête d'ou mon énorme disclaimer à ce sujet. Le temps fera son travail.

---

## ⚠️ DISCLAIMER — À LIRE AVANT TOUT

> **CE PROJET EST UN PROTOTYPE PERSONNEL.**
>
> Picsou a été développé pour un usage strictement **personnel et local**. Il n'a **pas** vocation à être déployé sur un serveur public ou partagé avec d'autres utilisateurs.
>
> **Pourquoi ne pas l'exposer sur internet :**
> - Il stocke des données bancaires hautement sensibles (soldes, transactions, tokens de session)
> - L'authentification est mono-utilisateur et basique (pas de 2FA, pas d'audit log)
> - Il n'a subi **aucun audit de sécurité professionnel**
> - Il interagit avec vos vraies banques via PSD2 (Enable Banking) et Trade Republic
>
> **Si vous l'utilisez sur un réseau exposé**, vous le faites à vos propres risques. L'auteur décline toute responsabilité en cas de compromission, perte de données ou accès non autorisé à vos comptes bancaires.
>
> **Usage recommandé : uniquement en local, sur votre propre machine, derrière votre réseau domestique.**

---

## Fonctionnalités

- **Comptes** — LEP, PEA, Compte-titres, Crypto, Livret, Compte courant...
- **Objectifs** — Cible + deadline sur plusieurs comptes ; suivi de progression
- **Synchronisation bancaire** — Via Enable Banking (PSD2/OAuth) : Revolut, BoursoBank, et +2000 banques européennes
- **Prix en temps réel** — CoinGecko (crypto), Yahoo Finance (actions/ETF)
- **Graphiques** — Évolution du patrimoine, répartition, historique par compte
- **Trade Republic** — Import CSV ou sync automatique

---

## Installation (local uniquement)

### Prérequis

- Docker & Docker Compose v2
- Un compte Enable Banking (gratuit) si vous souhaitez la sync bancaire → https://enablebanking.com/

### 1. Cloner le dépôt

```bash
git clone https://github.com/Zoeille/picsou-finance.git
cd picsou
```

### 2. Configurer les secrets

```bash
cp .env.example .env
```

Éditer `.env` :

| Variable | Description | Commande pour générer |
|----------|-------------|----------------------|
| `POSTGRES_PASSWORD` | Mot de passe PostgreSQL | Chaîne aléatoire forte |
| `JWT_SECRET` | Clé de signature JWT (min. 32 car.) | `openssl rand -base64 48` |
| `APP_USERNAME` | Identifiant de connexion | Votre choix |
| `APP_PASSWORD_HASH` | Hash bcrypt du mot de passe | `htpasswd -bnBC 12 "" VOTRE_MDP \| tr -d ':\n'` |

Pour la sync Enable Banking (optionnel) :

| Variable | Description |
|----------|-------------|
| `ENABLEBANKING_APPLICATION_ID` | Depuis votre dashboard Enable Banking |
| `ENABLEBANKING_KEY_ID` | Depuis votre dashboard Enable Banking |
| `ENABLEBANKING_REDIRECT_URI` | `http://localhost:5173/sync/callback` |
| `ENABLEBANKING_PRIVATE_KEY_PATH` | Chemin vers votre clé RSA privée |

Générer la paire de clés RSA pour Enable Banking :

```bash
# Générer la clé privée
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/enablebanking.pem

# Extraire la clé publique (à uploader dans le dashboard Enable Banking)
openssl rsa -pubout -in secrets/enablebanking.pem -out enablebanking_public.pem
```

### 3. Lancer

```bash
docker compose up --build
```

Ouvrir http://localhost:5173

> Le premier démarrage télécharge les images Docker et compile le projet — comptez 2-3 minutes.

### 4. Se connecter

Utiliser le `APP_USERNAME` et le mot de passe (non hashé) configurés dans `.env`.

---

## Développement

### Backend (Spring Boot)

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Nécessite une instance PostgreSQL locale. Le profil `dev` active les logs SQL et devtools.

### Frontend (React + Vite)

```bash
cd frontend
npm install
npm run dev
```

Vite proxie automatiquement `/api/*` vers `localhost:8080`.

### Lancer les tests

```bash
cd backend && ./mvnw test
cd frontend && npm run lint && npm run build
```

---

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌────────────┐
│  React Frontend │────▶│  Spring Boot Backend  │────▶│ PostgreSQL │
│   (Vite :5173)  │◀────│     (Tomcat :8080)    │     │ (:5432)    │
└─────────────────┘     └──────────┬───────────┘     └────────────┘
                                   │
                    ┌──────────────┼──────────────┐
                    ▼              ▼               ▼
             Enable Banking    CoinGecko      Yahoo Finance
             (PSD2/OAuth)    (crypto prices) (stock/ETF prices)
```

- **Auth** — JWT en cookies HttpOnly + SameSite=Strict, refresh token rotation
- **Ports/Adapters** — `BankConnectorPort`, `PriceProviderPort` — swap de provider sans toucher à la logique métier
- **Flyway** — Migrations de schéma versionnées
- **Rate limiting** — Bucket4j sur les endpoints sensibles (login, sync)

---

## Sécurité

Voir [SECURITY.md](SECURITY.md) pour la politique de sécurité et le signalement de vulnérabilités.

---

## Licence

MIT — utilisation personnelle uniquement. Aucune garantie d'aucune sorte.
