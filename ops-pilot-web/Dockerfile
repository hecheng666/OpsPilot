# 构建阶段：Vite 打包
FROM node:20-alpine AS build
WORKDIR /app
COPY ops-pilot-web/package.json ops-pilot-web/package-lock.json ./
RUN npm ci
COPY ops-pilot-web/ .
RUN npm run build

# 运行阶段：Nginx 静态托管 + /api 反向代理（含 SSE）
FROM nginx:1.27-alpine
COPY ops-pilot-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
