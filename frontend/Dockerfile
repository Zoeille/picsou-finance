# ─── Stage 1: Build ────────────────────────────────────────────────────────
FROM node:20-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm install --quiet

COPY . .
RUN npm run build

# ─── Stage 2: Serve with Nginx (non-root) ──────────────────────────────────
# nginx-unprivileged runs entirely as the nginx user (no root), listening on 8080
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
