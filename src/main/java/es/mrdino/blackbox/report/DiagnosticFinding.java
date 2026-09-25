package es.mrdino.blackbox.report;

public record DiagnosticFinding(Severity severity, String title, String location, String evidence,
                                String probableCause, String impact, String observedTime,
                                String action, String verification) {
    public DiagnosticFinding(Severity severity, String title, String evidence,
                             String probableCause, String action) {
        this(severity, title, "Servidor / alcance global", evidence, probableCause,
                severity == Severity.CRITICAL ? "Puede causar caidas severas de rendimiento o perdida de servicio."
                        : severity == Severity.WARNING ? "Puede degradar el tick o agotar recursos si se mantiene."
                        : "Es una pista de diagnostico que debe correlacionarse con el resto de evidencias.",
                "Observado dentro de la ventana seleccionada.", action,
                "Aplica un solo cambio, repite la misma carga y compara el siguiente informe con el periodo anterior.");
    }

    public enum Severity { CRITICAL, WARNING, INFO }
}
