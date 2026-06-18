# ── Stage 1: Build ──
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . .
RUN npm run build

# ── Stage 2: Runtime ──
FROM nginx:alpine
# Remove the stock default site — its `server_name localhost` would otherwise
# beat our catch-all and cause /api/* to fall through to the static handler.
RUN rm -f /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
COPY mailer.conf /etc/nginx/conf.d/mailer.conf
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
