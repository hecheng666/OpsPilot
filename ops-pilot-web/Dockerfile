# 构建阶段：Vite 打包（Node 22 满足 element-plus 依赖的 @vueuse/core 引擎要求）
FROM node:22-alpine AS build
WORKDIR /app
ENV NODE_OPTIONS=--dns-result-order=ipv4first
COPY ops-pilot-web/package.json ops-pilot-web/package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY ops-pilot-web/ .
RUN npm run build

# 运行阶段：Nginx 静态托管 + /api 反向代理（含 SSE）
FROM nginx:1.27-alpine
COPY ops-pilot-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
