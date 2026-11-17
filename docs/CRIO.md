# CRI-O

## 1. ¿Qué es y cómo funciona?

**CRI-O** es un *container runtime* hecho solo para Kubernetes. Implementa la **CRI** (Container Runtime Interface), la interfaz por la que el kubelet pide crear pods y contenedores. Es la alternativa a containerd, que es lo que usa este clúster.

```
kubelet ──CRI (gRPC)──▶ CRI-O ──▶ conmon ──▶ runc / crun ──▶ contenedor
                           │
                           ├── containers/image    descarga imágenes del registro
                           ├── containers/storage  guarda imágenes y capas (/var/lib/containers)
                           └── CNI                 red del pod (aquí, flannel)
```

1. El kubelet le pide a CRI-O, por el socket `unix:///var/run/crio/crio.sock`, crear un pod.
2. CRI-O descarga la imagen si no la tiene, prepara el sistema de archivos del contenedor y llama al plugin CNI para darle red al pod.
3. Lanza el contenedor con un runtime OCI (`crun` o `runc`) a través de **conmon**, un proceso pequeño que vigila cada contenedor y guarda sus logs.
4. Reporta el estado al kubelet.

| | containerd | CRI-O |
|---|---|---|
| Propósito | Runtime general (Docker, Kubernetes, otros) | Solo Kubernetes |
| Versiones | Propias | Siguen a Kubernetes (CRI-O 1.37 ↔ Kubernetes 1.37) |
| Uso conocido | Por defecto en la mayoría de distribuciones | Por defecto en OpenShift |
| Herramienta de diagnóstico | `crictl` (también `ctr`) | `crictl` |

Para Kubernetes son intercambiables: los pods, las imágenes y los manifiestos son los mismos.

## 2. ¿Se puede usar? Implementación sencilla

Se cambia **un solo worker**, `k8s-worker-david2`, de containerd a CRI-O, y se comprueba que los pods del Deployment corren ahí igual que en los demás.

> Antes de empezar, tomar una **instantánea** de la VM `k8s-worker-david2` en VirtualBox. Hacerlo después de tener funcionando las versiones y Traefik.

### 2.1 En el master: sacar el worker del clúster

```bash
kubectl drain k8s-worker-david2 --ignore-daemonsets --delete-emptydir-data
kubectl delete node k8s-worker-david2
sudo kubeadm token create --print-join-command
```

Guardar el comando `kubeadm join` que imprime el último paso.

### 2.2 En `k8s-worker-david2`: quitar containerd

```bash
sudo kubeadm reset -f --cri-socket unix:///run/containerd/containerd.sock
sudo rm -rf /etc/cni/net.d
sudo ip link delete cni0 2>/dev/null
sudo ip link delete flannel.1 2>/dev/null
sudo systemctl disable --now containerd
```

### 2.3 En `k8s-worker-david2`: instalar CRI-O 1.37

```bash
CRIO_VERSION=v1.37
curl -fsSL https://download.opensuse.org/repositories/isv:/cri-o:/stable:/$CRIO_VERSION/deb/Release.key \
  | sudo gpg --dearmor -o /etc/apt/keyrings/cri-o-apt-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/cri-o-apt-keyring.gpg] https://download.opensuse.org/repositories/isv:/cri-o:/stable:/$CRIO_VERSION/deb/ /" \
  | sudo tee /etc/apt/sources.list.d/cri-o.list
sudo apt-get update
sudo apt-get install -y cri-o

# Usar la red de flannel, no el puente de ejemplo que trae CRI-O
sudo rm -f /etc/cni/net.d/*crio*

sudo systemctl enable --now crio
sudo systemctl is-active crio
sudo crictl --runtime-endpoint unix:///var/run/crio/crio.sock version
```

`is-active` debe decir `active` y `crictl version` debe mostrar `RuntimeName: cri-o`.

### 2.4 En `k8s-worker-david2`: volver a unirse, ahora con CRI-O

Usar el comando del paso 2.1, con `sudo` delante y el socket de CRI-O al final:

```bash
sudo kubeadm join 100.105.119.107:6443 --token TOKEN --discovery-token-ca-cert-hash sha256:HASH \
  --cri-socket unix:///var/run/crio/crio.sock
```

La IP del nodo sigue siendo la de Tailscale, porque `/etc/default/kubelet` no se tocó.

### 2.5 Comprobar

En el master:

```bash
kubectl get nodes -o wide
```

La columna `CONTAINER-RUNTIME` debe mostrar `cri-o://1.37.x` en `k8s-worker-david2` y `containerd://2.x` en los demás.

Al sacar el worker, su pod de la aplicación se movió a otro worker. Para que vuelva a `k8s-worker-david2`, borrar uno de los dos pods del worker que quedó con 2:

```bash
kubectl get pods -n taller -o wide
kubectl delete pod -n taller NOMBRE_DE_UNO_DE_LOS_DOS
kubectl get pods -n taller -o wide | grep david2
```

En `k8s-worker-david2`, ver los pods y contenedores que maneja CRI-O:

```bash
sudo crictl --runtime-endpoint unix:///var/run/crio/crio.sock pods
sudo crictl --runtime-endpoint unix:///var/run/crio/crio.sock ps
sudo crictl --runtime-endpoint unix:///var/run/crio/crio.sock images
```

Deben aparecer los pods de flannel, kube-proxy e `info-api`. Por Traefik, `bash scripts/probar.sh 20` sigue mostrando los 5 pods, incluido el de `k8s-worker-david2`.

### 2.6 Volver a containerd (si hace falta)

En el master: `kubectl drain` y `kubectl delete node` como en 2.1. En el worker:

```bash
sudo kubeadm reset -f --cri-socket unix:///var/run/crio/crio.sock
sudo systemctl disable --now crio
sudo rm -rf /etc/cni/net.d
sudo systemctl enable --now containerd
sudo kubeadm join ... --cri-socket unix:///run/containerd/containerd.sock
```

O restaurar la instantánea de VirtualBox.
