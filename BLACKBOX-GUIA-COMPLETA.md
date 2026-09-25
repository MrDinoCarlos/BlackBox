# BlackBox: guía completa del plugin

## 1. Qué es BlackBox

BlackBox es un plugin de observabilidad, telemetría y diagnóstico para servidores Paper. Su objetivo es conservar suficiente contexto para responder preguntas operativas concretas:

- ¿Cuándo empezó a ir lento el servidor?
- ¿Cuánto duró el episodio y cuándo se recuperó?
- ¿Bajaron los TPS porque subió el coste de tick, porque aumentó la memoria, porque se cargaron chunks o porque un plugin hizo trabajo costoso?
- ¿Qué mundo y qué chunk concentraron los eventos?
- ¿Se crearon o retiraron entidades en el momento del problema?
- ¿Había hoppers, redstone, pistones, command blocks, explosiones o datapacks activos?
- ¿El proceso Java estaba saturando CPU, heap, GC, hilos, descriptores, disco o sockets?
- ¿El servidor acumuló ticks de retraso (`Can't keep up`) o sufrió un watchdog?
- ¿Qué plugin, dependencia, configuración o versión aparece en la cadena de error?
- ¿El problema empeoró después de un cambio de plugins o se recuperó cuando una fuente dejó de trabajar?

BlackBox no promete una causalidad matemática cuando la API de Paper no expone esa información. En esos casos calcula correlaciones, compara ventanas temporales y lo presenta como hipótesis con un nivel de confianza. La atribución fuerte se apoya en logs, stacks JFR y reproducción controlada.

El plugin es compatible con Paper 1.21.4–26.3 y está pensado para ejecutarse durante toda la vida del servidor. El muestreo y la escritura están diseñados para no hacer IO bloqueante en el hilo principal. Los informes y hashes se generan fuera del hilo de juego.

## 2. Cómo trabaja internamente

El flujo normal tiene seis etapas:

1. Durante `onLoad`, BlackBox conecta el diagnóstico al logger raíz y recupera errores que ya existían en `logs/latest.log`.
2. Durante `onEnable`, valida el YAML, comprueba tipos, rangos y claves desconocidas, crea el almacenamiento y registra los listeners.
3. Los monitores recogen muestras y eventos en memoria. La escritura se entrega a una cola asíncrona.
4. Los CSV se agrupan en segmentos temporales. Los segmentos cerrados se comprimen con GZIP y se aplican cuotas.
5. `status`, `scan`, `profile` y `report` permiten consultar o forzar acciones concretas.
6. En un apagado normal se captura el estado final y se genera el informe de sesión si está activado.

El estado observado no se mezcla con una única cifra. Cada fuente conserva su propio tiempo, ubicación y tipo de evidencia:

- `server` contiene las muestras globales.
- `events` contiene contadores del intervalo.
- `hotspots` conserva el mundo y las coordenadas del chunk donde ocurrió el evento.
- `chunks-` contiene observaciones periódicas de chunks cargados.
- `players-` contiene muestras individuales y `connections-` las transiciones de conexión.
- `entity-lifecycle-` conserva spawn y retirada de entidades.
- `chunk-lifecycle-` conserva carga, descarga, duración y tickets.
- `warnings-` conserva warnings, errores y stacks.
- `profiles-`, `profile-plugins-` y `profile-io-` describen perfiles JFR.
- `sessions-` conserva los límites de cada sesión y el conjunto de plugins/datapacks.

El informe lee esas fuentes sobre una misma línea temporal. Eso permite comparar, por ejemplo, un episodio de TPS bajo con el número de spawns del mismo intervalo, el CPU observado y un stack JFR iniciado por el perfil automático.

## 3. Funciones disponibles

### 3.1 Estado instantáneo

`/blackbox status` muestra el último estado que conoce BlackBox:

- TPS de un minuto.
- MSPT medio.
- p95 de tick.
- CPU del proceso Java.
- Porcentaje de heap usado.
- Jugadores conectados.
- Chunks cargados.
- Entidades y block entities del último escaneo.
- Tiempo de trabajo del escáner y chunk más costoso del ciclo.
- Tamaño de la cola de escritura y filas descartadas.
- Estado del perfil JFR.
- Último censo de regiones en disco.

El comando no crea una nueva lectura completa: devuelve la última muestra ya capturada, por lo que es seguro utilizarlo durante un incidente.

### 3.2 Escaneo manual

`/blackbox scan` pone en cola un ciclo completo de chunks cargados. Si el escáner ya está trabajando, informa de ello en lugar de duplicar la cola.

Si está activo `offline-region-census`, el mismo comando solicita también el censo de archivos `.mca`. Ese censo no carga los chunks en Paper. Lee las cabeceras Anvil y, si está activado el parseo NBT, descomprime las entradas fuera del hilo principal.

### 3.3 Informes

`/blackbox report 30m`, `/blackbox report 6h`, `/blackbox report 2d` y valores equivalentes generan un PDF profesional, un Markdown y anexos CSV en segundo plano.

El informe compara la ventana solicitada con la ventana anterior de la misma duración. Además relaciona cambios de plugins, versiones, datapacks, errores, perfiles, sesiones y actividad de chunks.

Al apagar normalmente, `automatic-session-on-shutdown` crea `BlackBox-session-report.pdf` desde el arranque de la sesión hasta la captura final.

### 3.4 Perfiles JFR

`/blackbox profile 60` inicia un perfil Java Flight Recorder durante 60 segundos, limitado por `profiling.max-seconds`.

El perfil analiza:

- Muestras de CPU y métodos principales.
- Frames pertenecientes a plugins instalados.
- Eventos de garbage collection.
- Lecturas y escrituras de archivo.
- Lecturas y escrituras de socket.
- Tiempo total y máxima latencia observada de E/S.

El archivo `.jfr` se puede abrir en IntelliJ Profiler o JDK Mission Control para flame graphs, hilos, locks y eventos detallados.

Con el perfil automático activado, varias muestras consecutivas por debajo de `tps-threshold` o por encima de `tick-p95-ms` inician un JFR corto. El cooldown evita iniciar perfiles continuamente durante un único incidente.

## 4. Qué mide BlackBox

### 4.1 TPS, MSPT y ticks

Cada muestra de servidor conserva:

- TPS de 1, 5 y 15 minutos.
- MSPT medio de Paper.
- Percentiles p50, p95 y p99 de tick.
- Tick máximo observado.

TPS describe cuántos ticks logra ejecutar el servidor. MSPT describe cuánto tarda cada tick. El presupuesto habitual de 20 TPS es aproximadamente 50 ms por tick. Un p95 alto significa que la lentitud es frecuente, aunque el promedio parezca normal.

El informe crea episodios cuando el TPS cae hasta `diagnostics.low-tps` o el p95 alcanza `diagnostics.high-mspt`. Un episodio contiene:

- Inicio.
- Peor muestra.
- Primer punto de recuperación, si aparece.
- Duración estimada.
- TPS mínimo.
- p95 máximo.
- MSPT medio antes, durante y después.
- Señal que lo inició.
- Causa probable.
- Evidencia coincidente.
- Confianza.
- Acción recomendada y método de verificación.

### 4.2 CPU y carga del sistema

Se conservan CPU del proceso Java, CPU total del sistema, carga media, número de procesadores disponibles y tiempo de actividad de la JVM.

La CPU del proceso indica que el trabajo está dentro de Java, pero no asigna automáticamente ese trabajo a una entidad o chunk. Para atribuir métodos o plugins se usa JFR.

La CPU del sistema ayuda a distinguir un servidor Java saturado de un host o contenedor compartido que está limitando el proceso.

### 4.3 Memoria y GC

Se registran:

- Heap usado y máximo.
- Memoria no-heap.
- Buffers directos y mapeados.
- Memoria física total y libre.
- Swap total y libre.
- Memoria virtual comprometida.
- Recuentos y tiempo acumulado de recolectores de basura.

El analizador observa si heap, RAM física o tasa de GC suben en el episodio. No interpreta automáticamente un heap alto como fuga: compara si vuelve a bajar y si coincide con pausas.

### 4.4 Hilos, deadlocks y límites del proceso

Cada muestra conserva número de hilos, deadlocks, clases cargadas y descriptores abiertos cuando el sistema operativo los expone.

Un deadlock se marca como crítico porque varios hilos están esperando recursos retenidos entre sí. La corrección requiere conservar el perfil y, si es posible, un thread dump del host.

Un porcentaje alto de descriptores puede indicar archivos o sockets que no se cierran, pero también un límite bajo del contenedor. El informe separa ambas posibilidades y recomienda comprobar el límite del sistema.

### 4.5 Disco y E/S

El muestreo global conserva espacio libre disponible en el directorio de datos. El perfil JFR añade datos de operaciones:

- Bytes leídos y escritos en sockets.
- Bytes leídos y escritos en archivos.
- Tiempo acumulado de E/S.
- Máxima duración de una operación de archivo.
- Máxima duración de una operación de socket.

La telemetría no puede medir la latencia física completa del proveedor de hosting. Para esa parte hay que contrastar con las métricas del panel del proveedor o con un agente del sistema operativo.

### 4.6 Red y jugadores

Por jugador se puede conservar:

- Ping.
- UUID y nombre, si la privacidad lo permite.
- Mundo y coordenadas.
- Protocolo.
- Marca del cliente.
- Locale.
- Distancias de visión, simulación y envío.
- Chunks enviados.

Las conexiones conservan joins, quits, kicks y estados del resource pack. Las direcciones se guardan como hash salado por defecto.

El servidor no recibe FPS, GPU, shaders, mods del cliente ni memoria del cliente mediante el protocolo normal. Esas métricas solo pueden llegar con un mod cliente complementario.

## 5. Escaneo de chunks

El escáner reparte los chunks cargados entre ticks. Para cada observación puede conservar:

- Entidades totales y por tipo.
- Items y living entities.
- Item frames.
- Jugadores presentes.
- Block entities por tipo.
- Hoppers.
- Spawners.
- Command blocks.
- Carga forzada.
- Tickets de plugins.
- Tiempo empleado escaneando ese chunk.

La métrica de coste del escáner es el coste de leer el chunk, no el CPU total que Paper gastó en ese chunk durante todos sus ticks. El informe lo muestra como evidencia de observación y no como atribución exacta de CPU.

Para cada hotspot localizado se incluye:

- Mundo.
- Coordenada de chunk.
- Centro X/Z en bloques.
- Comando `/tp @s X ~ Z`.

Esto permite ir directamente a una granja, reloj, acumulación de entidades o command block sospechoso.

## 6. Eventos y hotspots

Los contadores de eventos se reinician por intervalo de muestreo. Se conservan:

- Cargas y descargas de chunks.
- Spawns y muertes de entidades.
- Cambios de redstone.
- Movimientos de inventario/hoppers.
- Pistones.
- Explosiones.
- Bloques rotos y colocados.

Cuando `event-hotspots` está activo, cada contador mantiene también la coordenada de chunk. El informe suma los datos por hotspot y compara su tiempo con las ventanas de MSPT alto.

Ejemplo de interpretación:

> Durante un episodio con MSPT alto aparecen 1.200 movimientos de hopper en `world:120:-34`, el mismo chunk concentra 300 spawns y el CPU del proceso sube 30 puntos.

Eso es una evidencia fuerte de que la instalación de ese chunk debe inspeccionarse. Sigue siendo necesario apagar o modificar una fuente cada vez y repetir la carga para confirmar el efecto.

## 7. Ciclo de vida de entidades

El monitor de entidades guarda eventos de spawn y retirada. La información disponible incluye:

- UUID, si está permitido.
- Tipo.
- Motivo de spawn.
- Causa de retirada.
- Mundo, posición y chunk.
- Ticks vividos.
- Vida observada.
- Estado ticking/persistent.
- Si proviene de spawner.
- Nombre personalizado.
- Pasajeros.
- Jugadores rastreados.
- Fuente atribuida cuando hay una señal cercana, por ejemplo un command block.

El informe agrupa por tipo y muestra spawns, retiradas y balance observado. El balance no es un censo instantáneo: representa spawns menos retiradas capturados durante la ventana.

Las entidades de vida muy corta pueden indicar comandos de summon repetidos, plugins que crean y eliminan objetos, granjas mal limitadas o mecánicas con churn elevado. El valor de `short-entity-life-ticks` marca el umbral usado para la interpretación.

## 8. Command blocks y datapacks

Los command blocks se inventarían por posición y conservan:

- Mundo y coordenadas.
- Material y tipo.
- Nombre.
- Comando completo.
- Estado condicional, facing y powered.
- Observaciones.
- Ejecuciones reales notificadas por Paper.
- Primera y última observación.
- Primera y última ejecución.

El análisis estático marca patrones de riesgo:

- Selectores `@e` sin límite espacial, tipo o cantidad.
- Bloques repetitivos.
- `summon` frecuente.
- `particle` con frecuencia o radio elevado.
- `forceload`.
- `fill` y `clone` masivos.
- `spreadplayers`.
- Cadenas `execute` complejas.
- Llamadas a funciones.

Las funciones de datapack se analizan en disco cuando `analyze-functions` está activo. Se cuentan comandos, `execute`, selectores amplios, schedules, mutaciones, summons y partículas. El riesgo estático sirve para decidir qué revisar; no sustituye un perfil durante la ejecución real.

## 9. Censo offline de regiones

El censo recorre carpetas `region/`, `entities/` y `poi/` de los mundos. No activa chunks y no ejecuta sus entidades.

Puede detectar:

- Archivos `.mca` y tamaño.
- Coordenadas de chunks presentes.
- Sectores asignados.
- Entradas malformadas.
- Errores de NBT.
- Entidades serializadas.
- Block entities.
- Ticks de bloque y fluido.
- Tipos de entidades y block entities.

Sirve para investigar un problema que ya no está activo en memoria, verificar daños de región y encontrar zonas densas en disco. Un chunk descargado no ejecuta ticks mientras está descargado; el censo describe lo que conserva el archivo.

## 10. Diagnóstico de logs y errores

BlackBox conecta un handler al logging de Java y, al arrancar, sincroniza también parte de `logs/latest.log`. Reconstruye stacks multilínea y agrupa firmas repetidas.

Clasifica errores en categorías como:

- Configuración.
- Código.
- Dependencia o versión.
- Archivos y permisos.
- Base de datos.
- Red.
- Recursos de la JVM.
- Mensaje de arranque sin excepción suficiente.

Los errores de configuración muestran la clave, valor recibido, valor efectivo y ubicación aproximada de la línea. Un YAML mal formado se diagnostica y no se sobrescribe automáticamente.

El analizador reconoce mensajes de Paper como `Can't keep up`, `ticks behind`, watchdog y volcados del hilo del servidor. Esos mensajes aparecen como evidencia de que el servidor acumuló retraso, no como explicación automática del método que lo provocó.

## 11. Crash reports

Se analizan los archivos `.txt` recientes de:

- `crash-reports/` del servidor.
- `plugins/BlackBox/client-crash-reports/` para informes entregados manualmente por jugadores.

El análisis extrae descripción, última causa `Caused by`, primer frame atribuible a código no perteneciente a Java/Paper y una solución sugerida.

Puede distinguir patrones de:

- Memoria JVM agotada.
- Watchdog y tick bloqueado.
- Clase o método incompatible.
- Excepción no controlada.

El crash report original no se modifica. BlackBox produce `crash-analysis.csv` y añade el hallazgo al PDF.

## 12. Plugins, dependencias y compatibilidad

El inventario de plugins incluye:

- Nombre y versión.
- Estado activo/desactivado.
- Clase principal y API.
- Autores.
- Dependencias obligatorias y opcionales.
- Orden `load-before`.
- Tareas síncronas/asíncronas.
- Listeners registrados.
- Comandos, aliases, permisos y propietarios.

BlackBox marca dependencias obligatorias ausentes, dependencias desactivadas y plugins que terminaron desactivados. Las excepciones concretas se cruzan con `startup-diagnostics.csv` y `warnings.csv`.

La compatibilidad no se puede demostrar únicamente porque dos plugins estén instalados. Una interacción real se valida cruzando:

1. Cambio de rendimiento antes/después.
2. Warnings o excepciones del mismo momento.
3. Presencia de frames de plugin en JFR.
4. Reproducción con una sola variable modificada.

Por eso el texto del informe diferencia "aparece en stacks" de "causa confirmada".

## 13. Análisis de causa y efecto

El analizador construye episodios agrupando muestras contiguas que superan los umbrales. Para cada episodio compara:

- Ventana anterior.
- Ventana de degradación.
- Ventana posterior.

Busca candidatos en varias familias:

- CPU de proceso y sistema.
- Heap, RAM física y GC.
- Chunks, entidades y jugadores.
- Eventos por intervalo.
- Hotspots por mundo y chunk.
- Spawns y retiradas de entidades.
- Warnings y excepciones.
- Perfiles JFR automáticos y plugins presentes en sus stacks.

El candidato se presenta con evidencia textual, ubicación, recomendación y confianza:

- `ALTA`: hay evidencia directa en un log o en un perfil JFR asociado.
- `MEDIA`: varias señales independientes coinciden en la misma ventana.
- `BAJA`: existe una correlación temporal que necesita reproducción.

Una recuperación se registra cuando aparece una muestra posterior que vuelve por debajo del umbral. Si la retirada de entidades o la caída de CPU coincide con ella, se muestra como pista de recuperación.

La interpretación correcta es causa posible → cambio recomendado → repetición controlada → comparación del siguiente informe.

## 14. Qué contiene el PDF

El PDF profesional incorpora:

1. Resumen ejecutivo e índice.
2. Rendimiento global con TPS, MSPT y p95.
3. Causa y efecto de los episodios.
4. Gráficas de CPU de proceso y sistema, heap, RAM física, hilos y disco libre.
5. Recursos JVM, GC, buffers, descriptores y deadlocks.
6. E/S de sockets y archivos observada durante perfiles JFR.
7. Historial de sesiones y comparación con el periodo anterior.
8. Hallazgos y acciones.
9. Actividad agregada.
10. Cronología de incidentes.
11. Hotspots, ciclo de chunks, entidades y errores.
12. Densidad por chunk.
13. Jugadores y conectividad.
14. Command blocks.
15. Topología de plugins y mundos.
16. Datapacks, resource packs y funciones.
17. Censo offline de regiones.
18. Cobertura, precisión y anexos.
19. Glosario de siglas.

El pie de página explica de forma breve TPS, MSPT, JVM, GC, JFR y E/S. El glosario define también p95/p99, RAM, API, JAR, MCA, POI, UUID, CSV, TP, FPS, GPU, DNS y TCP.

## 15. Anexos CSV y archivos de salida

Los anexos están pensados para filtrar y procesar datos fuera del PDF:

| Archivo | Contenido |
|---|---|
| `server-*.csv` | Muestras globales de servidor. |
| `players-*.csv` | Muestras individuales de jugadores. |
| `connections-*.csv` | Join, quit, kick y resource pack. |
| `events-*.csv` | Contadores por intervalo. |
| `hotspots-*.csv` | Eventos por mundo y chunk. |
| `chunks-*.csv` | Observaciones detalladas de chunks. |
| `chunk-lifecycle.csv` | Cargas, descargas, duración y tickets. |
| `entity-lifecycle.csv` | Spawn, retirada, vida y atribución. |
| `entity-summary.csv` | Resumen por tipo de entidad. |
| `command-blocks.csv` | Inventario de command blocks. |
| `command-executions.csv` | Ejecuciones observadas. |
| `command-block-analysis.csv` | Riesgo, impacto y solución por bloque. |
| `functions.csv` | Análisis de funciones de datapack. |
| `warnings.csv` | Warnings, errores y stacks agrupados. |
| `startup-diagnostics.csv` | Causas y soluciones de errores de arranque. |
| `incidents.csv` | Cronología de incidentes. |
| `performance-episodes.csv` | Episodios de degradación y recuperación. |
| `profiles.csv` | Inicio y final de perfiles JFR. |
| `profile-plugins.csv` | Frames de plugins encontrados en JFR. |
| `profile-io.csv` | Bytes y latencias de E/S de perfiles. |
| `crash-analysis.csv` | Crash reports analizados. |
| `plugin-analysis.csv` | Huella operativa y evidencia JFR por plugin. |
| `session-history.csv` | Inicios, apagados y cambios de entorno. |
| `plugins.csv` | Plugins, versiones, tareas y listeners. |
| `plugin-dependencies.csv` | Dependencias por relación. |
| `commands.csv` | Comandos y permisos. |
| `command-aliases.csv` | Aliases normalizados. |
| `worlds.csv` | Mundos, distancias y estado. |
| `datapacks.csv` | Datapacks y compatibilidad. |
| `datapack-features.csv` | Features declaradas. |
| `resource-pack.csv` | Resource pack configurado. |
| `files.csv` | Archivos, tamaños, fechas y hashes. |
| `file-config-entries.csv` | Claves sanitizadas de configuración. |
| `packs.csv` | Contenido agregado de ZIP/JAR. |
| `regions.csv` | Salud y cabeceras de regiones. |
| `disk-chunks.csv` | Chunks presentes en disco. |
| `report.md` | Versión accesible y buscable del informe. |

## 16. Configuración explicada

La configuración bonita incluida en `src/main/resources/config.yml` mantiene las mismas claves y valores, pero está agrupada en ocho bloques.

### Idioma y traducciones

La clave `language` acepta `en_US` y `es_ES`, y su valor predeterminado es `en_US`. Los archivos `lang/en_US.yml` y `lang/es_ES.yml` contienen los textos del plugin y las traducciones del PDF, Markdown y análisis JFR. Sus claves deben conservarse para que MrDinoBot pueda actualizar los valores sin modificar el código.

### Muestreo

`sampling.server-seconds` controla la resolución de CPU, memoria, TPS y ticks. Cinco segundos es un equilibrio razonable. Para un incidente breve puede bajarse temporalmente a 1–2 segundos, aceptando más filas.

`sampling.chunk-scan-seconds` define cada cuánto se completa el censo en vivo. `sampling.chunks-per-tick` limita el trabajo instantáneo. Si el propio escaneo aparece como coste, baja este último antes de desactivar el diagnóstico.

`sampling.player-seconds` controla el detalle de latencia y actividad individual.

### Monitorización

Las opciones booleanas permiten reducir streams concretos cuando un servidor tiene una carga extrema. Para investigar entidades, chunks, redstone y hoppers se recomienda mantener `entity-lifecycle`, `chunk-lifecycle`, `event-hotspots` y `detailed-entities` activos.

`offline-region-census` y `region-parse-nbt` producen información muy útil para daños o densidad histórica, pero deben programarse con un intervalo razonable en mundos enormes.

### Inventario

Los límites protegen contra configuraciones y ZIP gigantes. `scan-config-values` sanitiza secretos reconocibles, pero el administrador debe revisar siempre quién tiene acceso a los informes.

### Almacenamiento

`retention-days` define el horizonte consultable. Las cuotas de telemetría, perfiles e informes son independientes. `queue-capacity`, `max-rows-per-stream-minute` y `max-row-chars` protegen el hilo principal y el disco ante eventos masivos.

Si aparece el hallazgo "Telemetría descartada", primero conserva el informe existente y luego decide si aumentar la cola, reducir eventos de alta frecuencia o ampliar cuota.

### Privacidad

`hash` permite relacionar una misma dirección sin guardar la dirección legible. `plain` solo debe usarse si existe una razón operativa y una política de acceso adecuada. `off` elimina la dirección del stream de conexiones.

Desactivar nombres, UUID o ubicaciones reduce la capacidad de correlacionar jugadores, conexiones y chunks.

### Umbrales

Los umbrales no cambian Paper. Cambian cuándo BlackBox crea advertencias, incidentes y hallazgos. `low-tps` debe ser mayor o igual que `critical-tps`. `correlation-warning` debe ser mayor o igual que `correlation-weak`.

### Informes

Los límites `pdf-*` controlan lo visible en el PDF. Los CSV son la fuente completa y deben consultarse cuando una tabla esté truncada.

### Profiling

`profiling.automatic.consecutive-samples` evita reaccionar a un pico aislado. `cooldown-minutes` evita llenar la cuota de perfiles durante un episodio sostenido.

## 17. Ejemplos de perfiles de uso

### Funcionamiento normal con bajo impacto

```yaml
sampling:
  server-seconds: 10
  chunk-scan-seconds: 120
  chunks-per-tick: 4
  player-seconds: 30
```

Mantiene una visión general y conserva más tiempo con menos filas.

### Investigación de lag en una granja

```yaml
sampling:
  server-seconds: 2
  chunk-scan-seconds: 30
  chunks-per-tick: 8
  player-seconds: 10

monitoring:
  entity-lifecycle: true
  chunk-lifecycle: true
  event-hotspots: true
  detailed-entities: true
```

Después de reproducir el episodio, usa `/blackbox report 30m` y revisa primero `performance-episodes.csv`, `hotspots.csv`, `entity-summary.csv` y `plugin-analysis.csv`.

### Captura de un problema de plugin

```yaml
profiling:
  automatic:
    enabled: true
    consecutive-samples: 2
    seconds: 60
    cooldown-minutes: 20
```

Reproduce una sola acción, espera a que termine el perfil y abre el `.jfr`. No concluyas por el nombre del plugin: comprueba el método y repite tras el cambio.

## 18. Procedimiento recomendado ante TPS bajos

1. Ejecuta `/blackbox status` para confirmar TPS, MSPT, CPU, heap y cola.
2. No reinicies inmediatamente si el servidor sigue respondiendo: deja que el perfil automático capture la evidencia.
3. Ejecuta `/blackbox report 30m` cuando el episodio haya terminado.
4. Abre `performance-episodes.csv` y localiza inicio, peor punto y recuperación.
5. Comprueba si coinciden CPU, GC, entidades, eventos y warnings.
6. Usa el mundo:chunk y el comando `/tp` del informe.
7. Si existe un perfil JFR, revisa los métodos superiores y los frames de plugins.
8. Cambia una sola fuente: apaga una granja, limita un selector, retira un ticket o desactiva una función.
9. Reproduce la misma carga con el mismo número de jugadores.
10. Genera otro informe y compara MSPT, p95, eventos/min, spawns/min y memoria.

No borres telemetría antes de exportar el informe. Los datos pueden mostrar la diferencia entre una recuperación real y un simple cambio de ventana de TPS.

## 19. Procedimiento ante ticks atrasados o watchdog

1. Conserva `logs/latest.log`, `warnings.csv` y el crash report si existe.
2. Busca `Can't keep up`, `ticks behind`, `watchdog` y `Server thread`.
3. Revisa el episodio que contenga la misma hora.
4. Abre el perfil JFR de esa ventana.
5. Identifica el primer frame propio de plugin o la operación de IO más lenta.
6. Comprueba si hay generación de chunks, guardado, GC o un lock.
7. Corrige una única causa y repite.

El mensaje de ticks atrasados confirma el síntoma. El stack JFR y los eventos cercanos son los que permiten decidir la corrección.

## 20. Procedimiento ante error de configuración o compatibilidad

1. Lee `startup-diagnostics.csv` antes de editar archivos.
2. Comprueba la clave y la línea indicada.
3. Compara el tipo y el rango con el `config.yml` incluido.
4. Si aparece una dependencia ausente, revisa `plugin-dependencies.csv`.
5. Si un plugin está desactivado, busca su primera excepción, no la última repetición.
6. Comprueba Java, Paper, API y copias duplicadas del JAR.
7. Revisa `crash-analysis.csv` si el proceso llegó a generar crash report.

## 21. Límites importantes

BlackBox no recibe desde Paper la CPU exacta consumida por cada entidad o chunk. La densidad, el evento y la coordenada son evidencias de contexto, no un reparto de CPU.

Paper no conserva retrospectivamente todos los paquetes de partículas. Un perfil puede mostrar el código que las genera durante la captura, pero no reconstruir partículas pasadas.

La red física, la latencia del almacenamiento del proveedor y los límites del contenedor pueden requerir métricas externas.

Los crash reports de cliente no se envían automáticamente. El jugador debe entregarlos y el administrador debe copiarlos a `plugins/BlackBox/client-crash-reports/`.

Una correlación de Pearson alta no prueba causalidad. El informe la usa para priorizar la investigación y recomienda un cambio aislado con verificación.

## 22. Gestión de datos y seguridad

La telemetría puede contener nombres, UUID, coordenadas, comandos y mensajes de error. Protege `plugins/BlackBox/`, los informes y los perfiles JFR como datos operativos del servidor.

Los valores de configuración reconocidos como secretos se redactan, pero no es posible reconocer todas las formas personalizadas de credenciales. Evita poner secretos en comandos, nombres o mensajes que vayan a quedar registrados.

Las cuotas eliminan primero los archivos más antiguos cuando se alcanza el límite. Ajusta retención y cuotas según el tamaño real del servidor y conserva fuera del servidor los informes que deban formar parte de una auditoría.

## 23. Resumen de capacidades

BlackBox puede observar el rendimiento global, localizar concentración por chunk, reconstruir actividad de entidades y eventos, revisar plugins/datapacks/configuración, analizar logs y crash reports, capturar stacks reales mediante JFR y producir un informe que conecte tiempo, señal, evidencia, hipótesis y acción correctiva.

Su valor principal está en conservar el contexto antes, durante y después del problema. El resultado no es únicamente "los TPS bajaron": muestra cuándo bajaron, cuánto duró, qué otras variables cambiaron, qué zonas estaban activas, qué errores aparecieron, qué evidencia respalda cada causa y cómo comprobar que la solución funcionó.
