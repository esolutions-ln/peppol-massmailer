# ── Stage 1: Build ──
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . .
RUN npm run build

# ── Stage 2: Runtime ──
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY mailer.conf /etc/nginx/conf.d/mailer.conf
EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
