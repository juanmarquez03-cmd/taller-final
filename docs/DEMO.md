# Pruebas y demostración

Todo se ejecuta en el **master**, dentro de `~/taller-final`. Conviene tener **tres terminales** (o `tmux`):

| Terminal | Comando | Para qué |
|---|---|---|
| T1 | `kubectl get pods -n taller -o wide -w` | Ver cómo se crean y terminan los pods |
| T2 | `bash scripts/probar.sh` | Ver qué versión y qué pod responde, cada 0,5 s |
| T3 | (libre) | Comandos de la demostración |

Antes de empezar:

```bash
kubectl get nodes                                  # 6 en Ready
kubectl get deployment,rs,pods -n taller -o wide   # Deployment 5/5, versión 1.0.0
kubectl get pods -n traefik -o wide                # Traefik en k8s-master
```

---

## Parte A. Deployment: versiones sin caída del servicio

### A.1 Versión 1 desplegada

```bash
kubectl get deployment info-api -n taller -o wide         # IMAGES: info-api:1.0.0
kubectl get rs -n taller                                  # un ReplicaSet con 5 pods
curl -s http://100.105.119.107:30080/api/version          # "version":"1.0.0"
```

### A.2 Pasar a la versión 2

Con T1 y T2 abiertas, en T3:

```bash
kubectl set image deployment/info-api info-api=ghcr.io/juanmarquez03-cmd/info-api:2.0.0 -n taller
kubectl annotate deployment/info-api kubernetes.io/change-cause="v2.0.0: cambio de versión" --overwrite -n taller
kubectl rollout status deployment/info-api -n taller
```

Qué se ve:
- **T1:** aparece un pod nuevo (versión 2) en `Pending` → `ContainerCreating` → `Running 1/1`. Solo cuando está listo, un pod viejo pasa a `Terminating`. Se repite hasta cambiar los 5. Nunca hay menos de 5 pods listos.
- **T2:** durante el cambio responden pods de las dos versiones (`v1.0.0` y `v2.0.0` mezclados) y **no aparece `[sin respuesta]`**. Al final todo responde `v2.0.0`.
- **T3:** `rollout status` muestra el avance y termina con `successfully rolled out`.

Ver los dos ReplicaSets (uno por versión):

```bash
kubectl get rs -n taller -o wide
```

El de la versión 1 queda con `DESIRED 0` (se guarda para poder volver atrás) y el de la versión 2 con 5.

**Qué explicar:** el Deployment crea un ReplicaSet por versión. Con `maxSurge: 1` y `maxUnavailable: 0` sube un pod nuevo, espera a que pase la `readinessProbe` y recién entonces baja uno viejo. Traefik solo envía tráfico a pods listos, por eso el servicio nunca se cae.

### A.3 Ver el historial de versiones

```bash
kubectl rollout history deployment/info-api -n taller
kubectl rollout history deployment/info-api -n taller --revision=1
kubectl rollout history deployment/info-api -n taller --revision=2
```

La columna `CHANGE-CAUSE` muestra el texto de la anotación `kubernetes.io/change-cause` (`v1.0.0: versión inicial`, `v2.0.0: cambio de versión`). `--revision=N` muestra la imagen de esa versión.

### A.4 Volver a una versión anterior

```bash
kubectl rollout undo deployment/info-api -n taller                   # a la versión anterior
kubectl rollout status deployment/info-api -n taller
curl -s http://100.105.119.107:30080/api/version                     # "version":"1.0.0"
kubectl rollout history deployment/info-api -n taller
```

El cambio también es gradual y sin cortes (se ve igual en T1 y T2). En el historial, la versión 1 reaparece con un número de revisión nuevo: Kubernetes no "retrocede" el contador, reutiliza el ReplicaSet viejo y le da la siguiente revisión.

Para ir a una revisión concreta:

```bash
kubectl rollout undo deployment/info-api -n taller --to-revision=N
```

### A.5 Ver y modificar el límite del historial

Ver el límite actual (5, definido en `revisionHistoryLimit`):

```bash
kubectl get deployment info-api -n taller -o jsonpath='{.spec.revisionHistoryLimit}{"\n"}'
kubectl get rs -n taller
```

En este punto hay 2 ReplicaSets (versión 1 y versión 2): uno activo y uno guardado.

Cambiar el límite a 0:

```bash
kubectl patch deployment info-api -n taller -p '{"spec":{"revisionHistoryLimit":0}}'
kubectl get rs -n taller
kubectl rollout history deployment/info-api -n taller
kubectl rollout undo deployment/info-api -n taller
```

Kubernetes borra el ReplicaSet guardado: queda solo el activo, el historial muestra una sola revisión y `rollout undo` falla porque no hay a dónde volver. Eso muestra para qué sirve el límite: cada ReplicaSet guardado es una versión a la que se puede regresar.

Dejarlo de nuevo en 5:

```bash
kubectl patch deployment info-api -n taller -p '{"spec":{"revisionHistoryLimit":5}}'
kubectl get deployment info-api -n taller -o jsonpath='{.spec.revisionHistoryLimit}{"\n"}'
```

A partir de aquí, cada nuevo cambio de versión vuelve a quedar guardado (hasta 5).

> `kubectl apply -f k8s/02-deployment.yaml` deja el Deployment como dice el archivo, incluida la imagen `1.0.0`. Si se aplica estando en la versión 2, vuelve a la 1. Para dejar la versión 2 como definitiva, cambiar el tag en el archivo, hacer commit y `git pull` en el master.

---

## Parte B. Balanceador Traefik

### B.1 Dónde está y cómo entra el tráfico

```bash
kubectl get pods -n traefik -o wide        # NODE: k8s-master
kubectl get svc -n traefik                 # 80:30080/TCP
kubectl get ingress -n taller              # reglas /api/info y /api/version
```

Ruta de una petición: `100.105.119.107:30080` → pod de Traefik en el master → Traefik elige un pod de `info-api` con **round robin** → el pod responde.

### B.2 Comprobar el round robin

```bash
bash scripts/probar.sh 20
```

Con 5 pods y 20 peticiones, cada pod atiende **4**, y los nombres se repiten **en el mismo orden** cada 5 líneas (rotación). El resumen al final cuenta las peticiones por pod.

Comparación con kube-proxy (reparto al azar), entrando directo al Service sin Traefik:

```bash
SVC=$(kubectl get svc info-api -n taller -o jsonpath='{.spec.clusterIP}')
for i in $(seq 1 20); do curl -s http://$SVC/api/version; echo; done | sed -n 's/.*"contenedor":"\([^"]*\)".*/\1/p' | sort | uniq -c
```

Aquí los conteos salen desiguales (por ejemplo 6, 2, 5, 3, 4) y sin un orden fijo: kube-proxy elige al azar por conexión.

### B.3 Dónde está definido el algoritmo

En **Traefik**, no en Kubernetes. Traefik lee el Ingress, consulta los pods listos del Service (EndpointSlices) y arma un balanceador propio cuyos servidores son las IPs de los pods. Su estrategia por defecto es **WRR (Weighted Round Robin)** con pesos iguales, que equivale a una rotación. No pasa por kube-proxy.

Se puede cambiar con el recurso `IngressRoute` de Traefik (campo `strategy`: `wrr`, `p2c`, `hrw`, `leasttime`) o asignando pesos con `TraefikService`. En este proyecto se deja el valor por defecto.

### B.4 Traefik durante un cambio de versión o una caída

Repetir la Parte A.2 con `bash scripts/probar.sh` en T2: Traefik deja de enviar peticiones a un pod apenas pasa a `Terminating` y empieza a usar el nuevo cuando está listo. También se puede borrar un pod (`kubectl delete pod -n taller NOMBRE`): el round robin sigue entre los que quedan y el reemplazo entra a la rotación.

---

## Preguntas probables

**¿Qué diferencia hay entre Deployment y ReplicaSet?**
El ReplicaSet mantiene una cantidad fija de pods iguales. El Deployment maneja varios ReplicaSets, uno por versión, y hace el cambio gradual, el historial y la vuelta atrás.

**¿Por qué nunca se cae el servicio al cambiar de versión?**
Por `maxUnavailable: 0` (nunca menos de 5 pods listos), `maxSurge: 1` (sube uno nuevo antes de bajar uno viejo), la `readinessProbe` (el pod nuevo recibe tráfico solo cuando responde) y el apagado ordenado de Spring Boot.

**¿Qué guarda el historial?**
Los ReplicaSets viejos con 0 réplicas. `revisionHistoryLimit` dice cuántos se guardan; cada uno es una versión a la que se puede volver.

**¿Por qué Traefik y no kube-proxy o Nginx?**
Ver `docs/DECISIONES.md`, sección 3.

**¿Dónde se define el algoritmo de balanceo?**
En Traefik: WRR por defecto, configurable por servicio con `IngressRoute`.

**¿Qué pasa si se cae el master?**
Traefik y el plano de control están ahí: se pierde la entrada por el 30080 y nadie recrea pods. Es el punto único de falla del clúster.
