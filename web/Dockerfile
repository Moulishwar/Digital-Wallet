# The web app: built with Node, served by nginx. The services are built by Cloud Native
# Buildpacks; a static site has no runtime to package, so a two-stage Dockerfile is the simpler
# and more conventional tool here.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
