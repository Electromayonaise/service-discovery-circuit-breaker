# Taller: Service Discovery y Circuit Breaker en Microservicios

Implementación del taller *"Patrones de Arquitectura de Microservicios"*: descubrimiento de servicios con
**Netflix Eureka** y tolerancia a fallos con **Resilience4j** (Circuit Breaker, Retry, Time Limiter y fallbacks).

| Servicio | Puerto | Origen | Rol |
|---|---|---|---|
| [fx-discovery-server](fx-discovery-server/) | 8761 | [jrquinte/FutureXEurekaServer](https://github.com/jrquinte/FutureXEurekaServer) | Servidor Eureka (registro de servicios) |
| [fx-course-service](fx-course-service/) | 8001 | [jrquinte/FutureXCourseApp](https://github.com/jrquinte/FutureXCourseApp) | API de cursos (JPA + H2 en memoria) |
| [fx-catalog-service](fx-catalog-service/) | 8002 | [jrquinte/FutureXCourseCatalog](https://github.com/jrquinte/FutureXCourseCatalog) | Catálogo: consume `fx-course-service` vía Eureka. **Aquí está la implementación de Resilience4j.** |

El primer commit del repositorio contiene el código original de los tres repositorios; el diff de la
implementación de Resilience4j se puede ver con `git diff HEAD~1 -- fx-catalog-service`.

---

## Requisitos y ejecución

- JDK 17 o superior (probado con JDK 21). No hace falta instalar Maven: cada proyecto trae su wrapper (`mvnw`).
- Arrancar **en este orden**, cada uno en su terminal (o ejecutando la clase `*Application` desde el IDE):

```bash
cd fx-discovery-server && ./mvnw spring-boot:run   # 1. FutureXEurekaServerApplication
cd fx-course-service   && ./mvnw spring-boot:run   # 2. FutureXCourseAppApplication
cd fx-catalog-service  && ./mvnw spring-boot:run   # 3. FutureXCourseCatalogApplication
```

> En Windows con PowerShell/cmd usar `.\mvnw.cmd spring-boot:run`.

**Ojo con el arranque:** Eureka no es instantáneo. El servidor publica los registros nuevos con hasta 30 s de
retraso (caché de respuestas) y el catálogo refresca su copia local cada 5 s (se bajó de los 30 s por defecto con
`eureka.client.registry-fetch-interval-seconds`). Si se llama a `http://localhost:8002/catalog` apenas arranca
todo, puede responder con el *fallback* (`No matches for the virtual host name :fx-course-service`) e incluso
abrir el circuito. Basta con esperar ~30 s después de levantar los servicios antes de empezar la demo.

---

## Parte 1 — Service Discovery con Eureka

### Cómo `fx-catalog-service` descubre y se comunica con `fx-course-service`

```
                    ┌───────────────────────────┐
  (1) registro +    │ fx-discovery-server :8761 │    (2) consulta del registro
      heartbeat     │         (Eureka)          │        (copia local cada 30 s)
        ┌──────────►└───────────────────────────┘◄──────────┐
        │                                                   │
┌───────┴───────────┐                             ┌─────────┴──────────┐
│ fx-course-service │◄────────────────────────────│ fx-catalog-service │
│       :8001       │  (3) GET :8001/courses      │       :8002        │
└───────────────────┘                             └────────────────────┘
```

1. **Registro.** Al arrancar, `fx-course-service` (y también el catálogo) se registra en Eureka con su nombre
   lógico `spring.application.name=fx-course-service`, su host, puerto y `homePageUrl`. La dependencia
   `spring-cloud-starter-netflix-eureka-client` y la propiedad `eureka.client.service-url.defaultZone`
   hacen esto automáticamente. Después envía un *heartbeat* cada 30 s; si Eureka deja de recibirlos (~90 s),
   el servidor desaloja la instancia.
2. **Descubrimiento.** El catálogo no conoce ninguna URL fija del servicio de cursos (en el código original quedó
   comentada la antigua `http://localhost:8080/`). En cada petición le pregunta a Eureka por el nombre lógico:

   ```java
   InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service", false);
   String courseAppURL = instanceInfo.getHomePageUrl();   // p. ej. "http://localhost:8001/"
   ```

   `getNextServerFromEureka` elige (en *round-robin*) una de las instancias `UP` registradas con ese nombre,
   así que si se levantan varias instancias del servicio de cursos la carga se reparte entre ellas.
   El segundo parámetro (`false`) indica que se quiere la URL HTTP y no la HTTPS.
3. **Llamada.** Con la URL descubierta se hace la petición HTTP normal:

   ```java
   courses = restTemplate.getForObject(courseAppURL + "/courses", String.class);
   ```

   (Spring normaliza la doble barra de `http://localhost:8001//courses`.)

En [CatalogController.java](fx-catalog-service/src/main/java/com/futurex/services/FutureXCourseCatalog/CatalogController.java)
esta lógica quedó en el método `getCourseAppURL()`, que usan todos los endpoints.

**Ventaja:** si el servicio de cursos cambia de host/puerto o se escala a N instancias, el catálogo no cambia:
solo depende del nombre lógico.

### Endpoints para la demostración

| URL | Respuesta esperada |
|---|---|
| http://localhost:8002/ | `Welcome to FutureX Course Catalog Course App Home` |
| http://localhost:8002/catalog | `Our courses are [{"courseid":1,"coursename":"Spring Boot Fundamentals",...}, ...]` |
| http://localhost:8002/firstcourse | `Our first course is Spring Boot Fundamentals` |
| http://localhost:8761/ | Panel de Eureka con `FX-CATALOG-SERVICE` y `FX-COURSE-SERVICE` registrados |
| http://localhost:8761/eureka/apps | Registro completo en XML/JSON (host, puerto, `homePageUrl`, estado) |

---

## Parte 2 — Circuit Breaker con Resilience4j

Todos los cambios están en `fx-catalog-service`, que es quien consume al otro servicio.

### 1. Dependencias ([pom.xml](fx-catalog-service/pom.xml))

- `spring-cloud-starter-circuitbreaker-resilience4j`: Resilience4j + integración con Spring Boot 3
  (anotaciones, configuración por properties) + la abstracción `CircuitBreakerFactory` de Spring Cloud.
- `spring-boot-starter-aop`: las anotaciones `@CircuitBreaker` / `@Retry` funcionan con aspectos (proxies AOP).
- `spring-boot-starter-actuator`: endpoints de salud y monitoreo del circuito (paso 7, opcional).

### 2. Configuración ([application.properties](fx-catalog-service/src/main/resources/application.properties))

Circuit Breaker `courseService`, con los valores del taller:

| Propiedad | Valor | Significado |
|---|---|---|
| `slidingWindowSize` | 10 | Se evalúan las últimas 10 llamadas |
| `minimumNumberOfCalls` | 5 | No se calcula la tasa de fallo hasta tener al menos 5 llamadas |
| `failureRateThreshold` | 50 | Si fallan ≥ 50 % → el circuito pasa a **OPEN** |
| `waitDurationInOpenState` | 5s | Tiempo en OPEN rechazando llamadas antes de volver a probar |
| `automaticTransitionFromOpenToHalfOpenEnabled` | true | Pasa a **HALF_OPEN** solo, sin esperar a una nueva petición |
| `permittedNumberOfCallsInHalfOpenState` | 3 | Llamadas de prueba en HALF_OPEN; si fallan < 50 % → **CLOSED**, si no → OPEN |
| `registerHealthIndicator` | true | Publica el estado en `/actuator/health` |
| `eventConsumerBufferSize` | 10 | Eventos guardados para `/actuator/circuitbreakerevents` |
| `ignoreExceptions` | `HttpClientErrorException` | Un 4xx es error del cliente, no falla del servicio: no abre el circuito |

Además se agregó un **Retry** `courseService` (`maxAttempts=3`, `waitDuration=500ms`) y se fijó el orden de
los aspectos para que quede `CircuitBreaker( Retry( llamada ) )`:

```properties
resilience4j.circuitbreaker.circuitBreakerAspectOrder=1
resilience4j.retry.retryAspectOrder=2
```

Por defecto Resilience4j aplica el Retry *por fuera* del Circuit Breaker. Con el `fallbackMethod` en
`@CircuitBreaker` (como pide el taller) eso haría que el Retry nunca viera la excepción y no reintentara nunca.
Con este orden: un fallo transitorio se reintenta; si los 3 intentos fallan el circuito registra **una** falla;
y con el circuito abierto no se reintenta nada (se responde de inmediato).

### 3 y 4. Anotaciones y métodos de fallback ([CatalogController.java](fx-catalog-service/src/main/java/com/futurex/services/FutureXCourseCatalog/CatalogController.java))

```java
@GetMapping("/catalog")
@CircuitBreaker(name = "courseService", fallbackMethod = "fallbackGetCatalog")
@Retry(name = "courseService")
public String getCatalog() { ... }

public String fallbackGetCatalog(Exception ex) {
    // devuelve el último catálogo obtenido con éxito, si existe (degradación elegante)
}
```

- Los tres endpoints (`/`, `/catalog`, `/firstcourse`) tienen su `@CircuitBreaker` + `@Retry` y su fallback
  (`fallbackGetCatalogHome`, `fallbackGetCatalog`, `fallbackGetSpecificCourse`).
- Un fallback debe tener el mismo tipo de retorno y los mismos parámetros que el método protegido, más la
  excepción al final. Se ejecuta tanto cuando la llamada falla como cuando el circuito está abierto
  (`CallNotPermittedException`); el mensaje incluye la causa para que se vea en la demo.
- `fallbackGetCatalog` no solo informa del error: si hubo una respuesta exitosa antes, la devuelve
  (`Last known courses are [...]`). El usuario sigue viendo el catálogo aunque el servicio de cursos esté caído.
- Las llamadas usan un `RestTemplate` compartido con **timeouts** (conexión 2 s, lectura 3 s). Sin timeouts, un
  servicio colgado (no caído) bloquearía los hilos del catálogo indefinidamente y el circuito nunca se enteraría.

### 5. Configuración avanzada ([Resilience4JConfig.java](fx-catalog-service/src/main/java/com/futurex/services/FutureXCourseCatalog/Resilience4JConfig.java))

La clase del paso 5 define un `Customizer<Resilience4JCircuitBreakerFactory>` con `failureRateThreshold(50)`,
`waitDurationInOpenState(1s)`, `slidingWindowSize(2)` y además un **TimeLimiter** de 4 s.

Esa configuración aplica a los circuitos creados *programáticamente* con el `CircuitBreakerFactory` de
Spring Cloud, no a los de las anotaciones (que salen de `application.properties`). Para que no quede como
código muerto, se agregó el endpoint `GET /catalog/factory`, que hace la misma llamada que `/catalog` pero así:

```java
circuitBreakerFactory.create("courseServiceFactory").run(
        () -> "Our courses are " + restTemplate.getForObject(...),
        throwable -> "Fallback (CircuitBreakerFactory): ...");
```

La clase también registra *listeners* que escriben en el log cada transición de estado del circuito
(`CircuitBreaker 'courseService': State transition from CLOSED to OPEN`) y cada reintento, útiles en la demo.

### 7. Monitoreo con Actuator

| URL | Qué muestra |
|---|---|
| http://localhost:8002/actuator/health | Estado de cada circuito (`CLOSED` / `CIRCUIT_OPEN` / `CIRCUIT_HALF_OPEN`), tasa de fallo, llamadas |
| http://localhost:8002/actuator/circuitbreakers | Estado y métricas de `courseService` y `courseServiceFactory` |
| http://localhost:8002/actuator/circuitbreakerevents | Últimos eventos (éxitos, errores, rechazos, transiciones) |
| http://localhost:8002/actuator/retries · `/actuator/retryevents` | Configuración y eventos del Retry |

---

## Demostración del Circuit Breaker (paso 6)

Con los tres servicios arriba, el script [scripts/demo-circuit-breaker.ps1](scripts/demo-circuit-breaker.ps1)
llama varias veces a un endpoint y muestra latencia, respuesta y estado del circuito en cada llamada:

```powershell
.\scripts\demo-circuit-breaker.ps1 -Path /catalog -Calls 10
```

Salida real de una ejecución durante el desarrollo del taller:

1. **Normal:** `/catalog` responde los cursos; el circuito está `CLOSED`.

   ```
   #1     45 ms  [CLOSED (fallidas=0/1, rechazadas=0)]  Our courses are [{"courseid":1,"coursename":"Spring Boot Fundamentals",...
   #2      8 ms  [CLOSED (fallidas=0/2, rechazadas=0)]  Our courses are [{"courseid":1,"coursename":"Spring Boot Fundamentals",...
   ```

2. **Detener `fx-course-service`** (Ctrl+C en su terminal o detenerlo en el IDE) y repetir las llamadas:

   ```
   #1   1026 ms  [CLOSED (fallidas=1/3, rechazadas=0)]  Fallback: Unable to retrieve course catalog. Last known courses are [...]
   #2   1028 ms  [CLOSED (fallidas=2/4, rechazadas=0)]  Fallback: ...
   #3   1045 ms  [OPEN (fallidas=3/5, rechazadas=0)]    Fallback: ...
   #4      2 ms  [OPEN (fallidas=3/5, rechazadas=1)]    Fallback: ...
   #5      2 ms  [OPEN (fallidas=3/5, rechazadas=2)]    Fallback: ...
   ```

   - Cada llamada con el circuito cerrado tarda ~1 s: son 3 intentos (Retry) con 500 ms entre ellos, y luego
     el fallback.
   - En la #3 se llega a 5 llamadas en la ventana (`minimumNumberOfCalls`) con 3 fallidas = 60 % ≥ 50 %, así
     que el circuito pasa a `OPEN`.
   - Desde la #4 las llamadas se rechazan sin intentar contactar al servicio caído (`rechazadas` sube) y el
     catálogo responde en ~2 ms. El usuario sigue recibiendo el último catálogo conocido.

3. **Reiniciar `fx-course-service`.** A los 5 s el circuito pasa solo a `HALF_OPEN`; las 3 llamadas de prueba
   salen bien y vuelve a `CLOSED`:

   ```
   #1    160 ms  [HALF_OPEN (fallidas=0/1, rechazadas=0)]  Our courses are [...]
   #2      7 ms  [HALF_OPEN (fallidas=0/2, rechazadas=0)]  Our courses are [...]
   #3      6 ms  [CLOSED (fallidas=0/0, rechazadas=0)]     Our courses are [...]
   ```

   En el log del catálogo se ven las transiciones:

   ```
   CircuitBreaker 'courseService': State transition from CLOSED to OPEN
   CircuitBreaker 'courseService': State transition from OPEN to HALF_OPEN
   CircuitBreaker 'courseService': State transition from HALF_OPEN to CLOSED
   ```

   Si en HALF_OPEN las llamadas de prueba vuelven a fallar (por ejemplo, el servicio aún no está registrado),
   se verá `HALF_OPEN to OPEN` y se repite el ciclo hasta que el servicio se recupere.

### Pruebas automatizadas

[CatalogControllerResilienceTests](fx-catalog-service/src/test/java/com/futurex/services/FutureXCourseCatalog/CatalogControllerResilienceTests.java)
verifica el comportamiento sin levantar Eureka ni el servicio de cursos (se simulan con mocks):

- la respuesta normal se arma con la URL que devuelve Eureka;
- una llamada fallida se reintenta 3 veces y el circuito registra una sola falla;
- tras 5 peticiones fallidas el circuito queda `OPEN` y las siguientes ya no llaman a Eureka ni al servicio;
- el fallback de `/catalog` devuelve el último catálogo conocido;
- los fallbacks de `/` y de `/catalog/factory`.

```bash
cd fx-catalog-service && ./mvnw test
```

---

## ¿Cómo mejora Resilience4j la resiliencia del sistema?

Sin Resilience4j, cuando `fx-course-service` se cae cada petición al catálogo termina en un **HTTP 500**, y
si el servicio está lento (no caído) cada petición ocupa un hilo del catálogo esperando. Con suficiente
tráfico el catálogo se queda sin hilos y cae también: una **falla en cascada**.

Resilience4j lo evita combinando patrones:

- **Circuit Breaker:** detecta que la dependencia está fallando (tasa de fallo sobre una ventana de
  llamadas) y deja de llamarla durante un tiempo (*fail fast*). Así no se desperdician recursos, se le da
  tiempo al servicio para recuperarse y luego se prueba automáticamente si ya se recuperó (HALF_OPEN).
- **Fallback:** en vez de un error, el usuario recibe una respuesta degradada pero útil (aquí, el último
  catálogo conocido).
- **Retry:** absorbe fallos transitorios (un reinicio, un paquete perdido) sin que el usuario los note.
- **Time Limiter + timeouts:** limitan cuánto se espera a una dependencia lenta.
- **Observabilidad:** estado y eventos de cada circuito en Actuator (y métricas para Micrometer/Prometheus).

Además es una librería liviana y modular (cada patrón es un módulo independiente: CircuitBreaker, Retry,
RateLimiter, Bulkhead, TimeLimiter, Cache), diseñada para Java 8+ y programación funcional (decoradores),
compatible con Spring Boot 3, y con proyecto activo. Por eso es la implementación que recomienda Spring Cloud.

## ¿Cómo mejora Hystrix la resiliencia del sistema?

**Hystrix** (Netflix, 2012) fue la librería que popularizó estos patrones en Java y el *Circuit Breaker*
original de Spring Cloud (`spring-cloud-starter-netflix-hystrix`). Resuelve el mismo problema de las fallas en
cascada:

- **Comandos aislados:** cada llamada remota se envuelve en un `HystrixCommand` (o la anotación
  `@HystrixCommand(fallbackMethod = "...")` con `@EnableCircuitBreaker`).
- **Aislamiento por pool de hilos (Bulkhead):** por defecto cada dependencia se ejecuta en su propio pool
  de hilos (10 hilos por defecto). Si una dependencia se vuelve lenta solo agota *su* pool, y el resto de la
  aplicación sigue respondiendo. También permite aislamiento por semáforo.
- **Timeouts:** cada comando tiene un timeout (1 s por defecto); si se excede se aborta y se usa el fallback.
- **Circuit Breaker:** en una ventana deslizante de 10 s, si hubo al menos 20 peticiones y el 50 % o más
  fallaron, el circuito se abre; tras 5 s deja pasar una petición de prueba para decidir si se cierra.
- **Fallback:** respuesta alternativa cuando el comando falla, hace timeout, se rechaza por pool lleno o el
  circuito está abierto.
- **Request caching y request collapsing:** evita llamadas duplicadas dentro de una misma petición y agrupa
  varias llamadas en una sola.
- **Monitoreo en tiempo real:** Hystrix Dashboard y Turbine (agregación de métricas de varias instancias).

**Estado actual:** Hystrix está en **modo mantenimiento desde 2018** (Netflix dejó de desarrollarlo) y Spring
Cloud lo **eliminó a partir de la versión 2020.0**, por lo que no funciona con Spring Boot 3. Por eso este
taller usa Resilience4j.

### Hystrix vs. Resilience4j

| | Hystrix | Resilience4j |
|---|---|---|
| Estado | Mantenimiento desde 2018; fuera de Spring Cloud desde 2020.0 | Activo; integración oficial con Spring Boot 3 |
| Diseño | Monolítico: todo pasa por `HystrixCommand` | Modular: se combinan solo los decoradores necesarios |
| Aislamiento | Pool de hilos por dependencia (por defecto) | Semáforo por defecto; `ThreadPoolBulkhead` opcional |
| Ventana del circuito | Por tiempo (10 s) | Por cantidad de llamadas o por tiempo, configurable |
| Detección de lentitud | Solo timeout | Además, umbral de llamadas lentas (`slowCallRateThreshold`) |
| Patrones extra | Cache y collapsing de peticiones | Retry, RateLimiter, TimeLimiter, Bulkhead, Cache |
| Monitoreo | Hystrix Dashboard / Turbine | Actuator + Micrometer (Prometheus, Grafana) |
| Configuración | Anotación + `hystrix.command.*` | Anotaciones + `resilience4j.*` en yml/properties, o código |

---

## Notas

- Se mantuvo el código y la configuración originales de `fx-discovery-server` y `fx-course-service`. Para el
  alcance de un taller local está bien, pero no deberían desplegarse así: Eureka no tiene autenticación, la
  consola H2 está habilitada sin contraseña y los endpoints `POST /courses` y `DELETE /{id}` no tienen
  autenticación.
