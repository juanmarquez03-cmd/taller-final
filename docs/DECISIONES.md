# Decisiones técnicas

Qué se decidió y por qué, en los tres puntos desarrollados del plan de trabajo final (CRI-O, Deployment y balanceador).

## 0. Uso de IA

El código, los manifiestos y la documentación fueron generados con **Claude (Anthropic)**. El grupo revisó el código, publicó las imágenes, desplegó en el clúster y ejecutó las pruebas.

---

## 1. Aplicación

**Decisión:** dos endpoints en Spring Boot 4.1 (Java 21):
- `GET /api/info`: hora del contenedor, integrantes, contenedor, almacenamiento (MB) y RAM (MB).
- `GET /api/version`: versión de la aplicación y contenedor que responde.

**Por qué:**
- `/api/info` se conserva igual que en la entrega anterior.
- `/api/version` es corto a propósito: con solo la versión y el pod se ve en vivo qué versión responde y desde dónde, que es lo que hay que comprobar al cambiar de versión y al probar el balanceador.
- La aplicación no guarda estado, así que cualquier pod de cualquier versión puede atender cualquier petición.
- El contenedor se identifica por su hostname (en Kubernetes, el nombre del pod); la RAM se lee del cgroup del contenedor (límite de 384 Mi) y el almacenamiento del sistema de archivos `/`.

---

## 2. Deployment y control de versiones

### 2.1 La versión vive en el tag de la imagen

**Decisión:** cada versión es una imagen distinta: `info-api:1.0.0`, `info-api:2.0.0`. El número lo pone el tag de Git (`v2.0.0`) al construir la imagen en GitHub Actions, que lo pasa a la aplicación en la variable `APP_VERSION`.

**Por qué:**
- Cambiar de versión en Kubernetes es cambiar la imagen del Deployment; con tags distintos, cada versión es identificable y se puede volver a cualquiera.
- Se descartó usar `latest`: con una sola etiqueta no hay forma de distinguir versiones ni de volver atrás.
- `imagePullPolicy: IfNotPresent`: una versión publicada no cambia, así que cada worker la descarga una sola vez.

### 2.2 Cambio gradual sin caída

**Decisión:** estrategia `RollingUpdate` con `maxSurge: 1` y `maxUnavailable: 0`.

**Por qué:** el plan pide que se mantenga siempre el mínimo de réplicas. Con estos valores el Deployment sube 1 pod nuevo (6 en total), espera a que esté listo (`readinessProbe`) y recién entonces baja 1 viejo. Nunca hay menos de 5 pods atendiendo. Es más lento que cambiar todos a la vez, pero es lo que garantiza el servicio continuo.

### 2.3 Historial

**Decisión:** `revisionHistoryLimit: 5` y la anotación `kubernetes.io/change-cause` en cada versión.

**Por qué:**
- Cada versión del Deployment es un ReplicaSet. Los viejos se guardan con 0 réplicas para poder volver con `kubectl rollout undo`; `revisionHistoryLimit` define cuántos se guardan (por defecto Kubernetes guarda 10). Con 5 alcanza para las pruebas y se ve el efecto al modificarlo.
- `change-cause` es el texto que muestra `kubectl rollout history`; sin él, la columna sale vacía y no se sabe qué es cada revisión.

### 2.4 Reparto entre versiones

**Decisión:** `topologySpreadConstraints` con `maxSkew: 1` por nodo y `matchLabelKeys: [pod-template-hash]`.

**Por qué:** mantiene un pod por worker. `matchLabelKeys` hace que el reparto se calcule solo entre pods de la misma versión; sin eso, durante el cambio los pods viejos y nuevos se mezclan en el cálculo y el resultado puede quedar con 2 pods en un worker y 0 en otro (ya se observó en entregas anteriores).

---

## 3. Balanceador de carga: Traefik

### 3.1 ¿Cuál usar y por qué?

| | kube-proxy | Nginx (ingress-nginx) | **Traefik** |
|---|---|---|---|
| Qué es | Componente de Kubernetes en cada nodo | Controlador Ingress basado en Nginx | Controlador Ingress y proxy HTTP |
| Nivel | Capa 4 (conexiones TCP) | Capa 7 (HTTP) | Capa 7 (HTTP) |
| Reparto | Al azar por conexión (modo iptables/nftables) | Round robin | **Round robin (WRR)** por defecto, configurable |
| Rutas por URL (`/api/info`, `/api/version`) | No | Sí | Sí |
| Estado del proyecto | Parte de Kubernetes | **Retirado en marzo de 2026**: sin actualizaciones ni parches de seguridad | Mantenido |
| Instalación | Ya viene | Helm | Helm, un solo pod |

**Decisión:** Traefik.

**Por qué:**
- kube-proxy no es un balanceador que se pueda elegir ni configurar: reparte al azar y no entiende HTTP, así que no permite probar un algoritmo definido ni enrutar por URL.
- El proyecto comunitario ingress-nginx fue retirado por Kubernetes en marzo de 2026; instalarlo hoy sería usar software sin parches de seguridad.
- Traefik lee directamente los Ingress y los pods de Kubernetes, se instala con un comando, es liviano (importa porque las VMs tienen poca RAM) y su algoritmo es conocido y configurable.

### 3.2 Dónde está definido el algoritmo

En **Traefik**. A partir del Ingress, Traefik crea un "servicio" interno cuyos servidores son las **IPs de los pods listos** (las lee de los EndpointSlices del Service `info-api`). La estrategia por defecto de ese servicio es **WRR (Weighted Round Robin)**; con pesos iguales es una rotación: pod 1, pod 2, …, pod 5, pod 1… Las peticiones no pasan por kube-proxy.

Se puede cambiar por servicio con el recurso `IngressRoute` de Traefik (campo `strategy`: `wrr`, `p2c`, `hrw`, `leasttime`) o dar pesos distintos con `TraefikService`. En este proyecto se usa el valor por defecto.

### 3.3 Cómo se prueba

`scripts/probar.sh 20` hace 20 peticiones por Traefik: con 5 pods, cada uno atiende 4 y el orden se repite cada 5. Haciendo lo mismo directo al Service (kube-proxy), los conteos salen desiguales y sin orden. Detalle en `docs/DEMO.md`, Parte B.

### 3.4 Ubicación y entrada

**Decisión:** un pod de Traefik fijado en `k8s-master` (`nodeSelector` del rol control-plane y `toleration` de su taint), con Service `NodePort` en el puerto **30080**. El Service de la aplicación es `ClusterIP`.

**Por qué:**
- Se pidió el balanceador en el master. El master siempre está encendido en las pruebas, mientras que los workers se apagan.
- La entrada única es `http://100.105.119.107:30080` dentro de la red Tailscale. Exponerlo fuera de esa red es el punto 4 del plan, que todavía no se desarrolla.
- La aplicación queda con un Service `ClusterIP` (interno): todo el tráfico externo entra por Traefik.
- No se habilita HTTPS ni el dashboard de Traefik porque no hacen falta para estas pruebas.

---

## 4. CRI-O

**Decisión:** implementación sencilla: un solo worker (`k8s-worker-david2`) cambia containerd por CRI-O; los demás siguen con containerd.

**Por qué:**
- Demuestra que Kubernetes funciona con cualquier runtime que implemente la interfaz CRI: el mismo Deployment corre pods en nodos con containerd y con CRI-O.
- Cambiar un solo worker no arriesga el resto del clúster y se puede revertir.
- Se usa CRI-O **v1.37**, la misma versión menor de Kubernetes, como recomienda el proyecto CRI-O.

Explicación y pasos en `docs/CRIO.md`.
