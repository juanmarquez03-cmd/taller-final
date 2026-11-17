# Info API: proyecto final de Kubernetes

**Sistemas Distribuidos · UPTC · Ingeniería de Sistemas y Computación**

Integrantes:
- Nicolas David Castro Cely
- Juan David Márquez Pinto
- Andres Mauricio Sanchez Sanchez

API en Spring Boot desplegada en un clúster Kubernetes de 6 nodos unidos por Tailscale. Cubre los puntos 1, 2 y 3 del plan de trabajo final:

1. **CRI-O**: cómo funciona y uso en un worker ([docs/CRIO.md](docs/CRIO.md)).
2. **Deployment**: cambio de versión sin caída del servicio, historial y vuelta atrás.
3. **Balanceador de carga**: Traefik en el master, con round robin entre los pods.

Documentos:
- [docs/DECISIONES.md](docs/DECISIONES.md): decisiones técnicas y por qué.
- [docs/DEMO.md](docs/DEMO.md): pruebas de versiones y del balanceador, paso a paso.
- [docs/CRIO.md](docs/CRIO.md): CRI-O, explicación e implementación.

---

## 1. Infraestructura

| Nodo | Rol | Dueño | IP de Tailscale |
|---|---|---|---|
| `k8s-master` | control-plane + **Traefik** | Nicolas | 100.105.119.107 |
| `k8s-worker1` | worker | Nicolas | — |
| `k8s-worker-andres1` | worker | Andres | — |
| `k8s-worker-andres2` | worker | Andres | — |
| `k8s-worker-david1` | worker | Juan David | 100.72.197.114 |
| `k8s-worker-david2` | worker | Juan David | 100.82.10.14 |

Kubernetes v1.37.1 (kubeadm), red de pods flannel, VMs Ubuntu 26.04 en VirtualBox.

```
Petición ──▶ master:30080 ──▶ Traefik (pod en el master)
                                  │ round robin
              ┌──────────┬────────┼────────┬──────────┐
              ▼          ▼        ▼        ▼          ▼
            pod        pod      pod      pod        pod      ← Deployment info-api
          worker1   andres1  andres2   david1     david2        (5 réplicas)
```

| Objeto | Archivo | Función |
|---|---|---|
| Namespace `taller` | `k8s/01-namespace.yaml` | Agrupa todo lo de la aplicación |
| Deployment `info-api` | `k8s/02-deployment.yaml` | 5 pods, uno por worker; controla las versiones |
| Service `info-api` (ClusterIP) | `k8s/03-service.yaml` | Agrupa los pods; interno al clúster |
| Ingress `info-api` | `k8s/04-ingress.yaml` | Reglas de Traefik para `/api/info` y `/api/version` |
| Traefik | `traefik/values.yaml` | Balanceador, instalado con Helm en el master, puerto 30080 |

---

## 2. Endpoints

| Ruta | Respuesta |
|---|---|
| `GET /api/info` | Hora del contenedor, integrantes, contenedor, almacenamiento (MB) y RAM (MB) |
| `GET /api/version` | Versión de la aplicación y contenedor que responde |

```json
// GET /api/info
{"horaContenedor":"2026-11-17T08:15:30-05:00","integrantes":["Nicolas David Castro Cely","Juan David Márquez Pinto","Andres Mauricio Sanchez Sanchez"],"contenedor":"info-api-6c9d8-x2k4p","almacenamientoMB":18258,"ramMB":384}

// GET /api/version
{"version":"1.0.0","contenedor":"info-api-6c9d8-x2k4p"}
```

La versión la define el **tag de Git** con el que se construye la imagen (`v1.0.0` → `1.0.0`). La versión 2 es el mismo código con el número `2.0.0`.

---

## 3. Qué se instala en cada máquina

| Máquina | Qué instalar |
|---|---|
| **Master** | `git` (ya está), **Helm** y **Traefik** (con Helm). Pasos en la sección 6 |
| **Workers** | Nada nuevo. Solo necesitan salida a internet para descargar las imágenes |
| **`k8s-worker-david2`** (solo para el punto 1) | **CRI-O**, que reemplaza a containerd en ese worker. Ver [docs/CRIO.md](docs/CRIO.md) |

Antes de empezar, en **cada worker** comprobar:

```bash
tailscale ip -4                        # su IP 100.x
sudo systemctl is-active kubelet       # active
curl -sI https://ghcr.io | head -1     # HTTP/2 ... (tiene internet)
```

Si un worker no logra descargar imágenes (el pod se queda en `ContainerCreating` con `Pulling image`), desactivar IPv6 en ese worker:

```bash
echo net.ipv6.conf.all.disable_ipv6 = 1 | sudo tee /etc/sysctl.d/99-disable-ipv6.conf
echo net.ipv6.conf.default.disable_ipv6 = 1 | sudo tee -a /etc/sysctl.d/99-disable-ipv6.conf
sudo sysctl --system
sudo systemctl restart containerd
```

---

## 4. Estructura del proyecto

```
taller-final/
├── pom.xml                         Spring Boot 4.1 (web), Java 21
├── Dockerfile                      Compila y genera la imagen; recibe APP_VERSION
├── .github/workflows/imagen.yml    Publica ghcr.io/<usuario>/info-api:<versión> con cada tag vX.Y.Z
├── k8s/
│   ├── 01-namespace.yaml
│   ├── 02-deployment.yaml
│   ├── 03-service.yaml
│   └── 04-ingress.yaml
├── traefik/values.yaml             Configuración de Traefik para Helm
├── scripts/probar.sh               Consulta /api/version por Traefik
├── docs/
│   ├── DECISIONES.md
│   ├── DEMO.md
│   └── CRIO.md
└── src/main/java/co/edu/uptc/distribuidos/infoapi/
    ├── InfoApiApplication.java
    ├── controller/InfoController.java   GET /api/info y GET /api/version
    ├── service/InfoService.java         Arma las respuestas
    ├── service/LectorRecursos.java      Lee hostname, disco y cgroup
    ├── model/InfoResponse.java          Respuesta de /api/info
    ├── model/VersionResponse.java       Respuesta de /api/version
    └── config/                          Integrantes y lector de recursos
```

---

## 5. Publicar las imágenes (GitHub)

1. Crear el repositorio **público** `taller-final` y subir el contenido de esta carpeta (**Add file → Upload files**). Si la carpeta `.github` no se sube al arrastrar, crear a mano el archivo `.github/workflows/imagen.yml` (**Add file → Create new file**) y pegar su contenido.
2. Dar permiso al repositorio para publicar en el paquete `info-api`: abrir `https://github.com/users/juanmarquez03-cmd/packages/container/info-api/settings` → **Manage Actions access** → **Add Repository** → `taller-final` → rol **Write**. La visibilidad del paquete debe ser **Public**.
3. **Versión 1:** pestaña **Releases → Draft a new release** → **Choose a tag** → escribir `v1.0.0` → **Create new tag** → **Publish release**. En **Actions** corre "Publicar imagen" y publica `ghcr.io/juanmarquez03-cmd/info-api:1.0.0`.
4. **Versión 2:** repetir con el tag `v2.0.0`. Publica `info-api:2.0.0` (mismo código, versión 2.0.0).

Esperar el ✅ verde de cada una antes de usarla. Si al crear el release no arranca el flujo, lanzarlo a mano: **Actions → Publicar imagen → Run workflow** y escribir la versión (`1.0.0` o `2.0.0`).

---

## 6. Montar en el clúster (en el master)

### 6.1 Borrar lo anterior y traer el proyecto

```bash
kubectl delete namespace taller          # la versión anterior (ReplicaSet); NotFound = no había nada
cd ~
git clone https://github.com/juanmarquez03-cmd/taller-final.git
cd taller-final
```

### 6.2 Instalar Helm

```bash
curl -fsSL -o get_helm.sh https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-4
chmod 700 get_helm.sh
./get_helm.sh
helm version
```

### 6.3 Instalar Traefik en el master

```bash
helm repo add traefik https://traefik.github.io/charts
helm repo update
helm install traefik traefik/traefik -n traefik --create-namespace -f traefik/values.yaml
kubectl get pods -n traefik -o wide       # Running, NODE = k8s-master
kubectl get svc -n traefik                # TYPE NodePort, PORT(S) 80:30080/TCP
kubectl get ingressclass                  # traefik
```

### 6.4 Desplegar la aplicación (versión 1)

```bash
kubectl apply -f k8s/
kubectl rollout status deployment/info-api -n taller
kubectl get pods -n taller -o wide
```

Deben quedar 5 pods `Running 1/1`, uno por worker.

### 6.5 Probar

```bash
curl -s http://100.105.119.107:30080/api/info
curl -s http://100.105.119.107:30080/api/version
bash scripts/probar.sh 10
```

El cambio de versión, el historial y las pruebas de Traefik están en [docs/DEMO.md](docs/DEMO.md).

---

## 7. Actualizar el proyecto en el master

Si se cambia algo del repositorio:

```bash
cd ~/taller-final && git pull
kubectl apply -f k8s/
```

## 8. Problemas frecuentes

| Síntoma | Causa probable | Solución |
|---|---|---|
| `ImagePullBackOff` | La versión no está publicada o el paquete es privado | Revisar el ✅ en Actions y que el paquete sea Public |
| Pod en `ContainerCreating` mucho tiempo con `Pulling image` | El worker no descarga | Desactivar IPv6 en ese worker (sección 3) |
| `curl` al 30080 responde `404 page not found` | Traefik no ve el Ingress | `kubectl get ingress -n taller` y `kubectl get ingressclass` |
| `curl` al 30080 no conecta | El pod de Traefik no está corriendo | `kubectl get pods -n traefik -o wide` |
| Pods en `Pending` | Falta RAM o hay menos de 5 workers en Ready | `kubectl describe pod -n taller NOMBRE` y `kubectl get nodes` |

## 9. Uso de IA

El código, los manifiestos y la documentación fueron generados con **Claude (Anthropic)**.
