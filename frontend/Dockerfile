FROM node:24.22.2-alpine AS build
WORKDIR /workspace
COPY frontend/package.json frontend/package-lock.json frontend/.npmrc ./
RUN npm install --global npm@11.19.1 && npm ci --ignore-scripts
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.mjs ./
COPY frontend/src/ src/
RUN npm run build

FROM nginx:1.30.7-alpine3.24
COPY config/nginx/frontend.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist/ /usr/share/nginx/html/
EXPOSE 8080
