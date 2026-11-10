# Filtro ruby de Logstash (logstash.conf -> ruby { path => ... }): separa cada lote OTLP en un evento por log.
#
# Cada linea de otel-collector.log es un lote OTLP en JSON (resourceLogs -> scopeLogs -> logRecords) con varios logs,
# a veces de los dos servicios. Por cada logRecord se crea un evento con campos planos (nombres de ECS):
#   @timestamp, message, log.level, log.severity_number, log.logger, service.name, trace.id, span.id,
#   attributes.* (atributos del log: thread.name, exception.type, ...) y resource.* (atributos del recurso).
#
# Va en un archivo y no en el "code" del filtro porque el codigo inline se evalua sobre un binding compartido y sus
# variables no son privadas de cada worker del pipeline: con varios workers se mezclaban campos de logs distintos
# (p. ej. el mensaje de un servicio con el logger del otro). Las variables locales de un metodo si lo son.
require "digest"

def register(params)
end

def filter(event)
  batch = event.get("otlp")
  unless batch.is_a?(Hash) && batch["resourceLogs"].is_a?(Array)
    event.tag("_not_otlp_logs")
    return [event]
  end

  # Id determinista = linea original + posicion del log dentro de ella: si Logstash vuelve a leer el archivo
  # (p. ej. al recrear el contenedor), cada log sobrescribe su propio documento en vez de duplicarse
  line_digest = Digest::SHA1.hexdigest(event.get("message").to_s)
  events = []

  batch["resourceLogs"].each do |rl|
    resource = otlp_attributes((rl["resource"] || {})["attributes"])
    (rl["scopeLogs"] || []).each do |sl|
      logger = (sl["scope"] || {})["name"]
      (sl["logRecords"] || []).each do |rec|
        events << log_event(rec, logger, resource, "#{line_digest}-#{events.size}")
      end
    end
  end

  # El lote original no se indexa: solo los eventos nuevos
  events
end

def log_event(rec, logger, resource, doc_id)
  e = LogStash::Event.new

  nanos = (rec["timeUnixNano"] || rec["observedTimeUnixNano"]).to_i
  nanos = (Time.now.to_f * 1_000_000_000).to_i if nanos == 0
  e.timestamp = LogStash::Timestamp.at(nanos / 1_000_000_000, (nanos % 1_000_000_000) / 1000.0)

  body = otlp_any_value(rec["body"])
  e.set("message", body.is_a?(String) ? body : body.to_s)
  e.set("[log][level]", rec["severityText"]) if rec["severityText"]
  e.set("[log][severity_number]", rec["severityNumber"]) if rec["severityNumber"]
  e.set("[log][logger]", logger) if logger
  e.set("[service][name]", resource["service.name"])
  e.set("[trace][id]", rec["traceId"]) if rec["traceId"].to_s != ""
  e.set("[span][id]", rec["spanId"]) if rec["spanId"].to_s != ""

  record_attrs = otlp_attributes(rec["attributes"])
  e.set("attributes", record_attrs) unless record_attrs.empty?
  e.set("resource", resource)
  e.set("[@metadata][doc_id]", doc_id)
  e
end

# Lista de KeyValue de OTLP -> Hash
def otlp_attributes(list)
  (list || []).each_with_object({}) { |kv, h| h[kv["key"]] = otlp_any_value(kv["value"]) }
end

# AnyValue de OTLP -> valor Ruby
def otlp_any_value(v)
  return nil unless v.is_a?(Hash)
  if v.key?("stringValue") then v["stringValue"]
  elsif v.key?("intValue") then v["intValue"].to_i
  elsif v.key?("doubleValue") then v["doubleValue"].to_f
  elsif v.key?("boolValue") then v["boolValue"]
  elsif v.key?("arrayValue") then (v["arrayValue"]["values"] || []).map { |x| otlp_any_value(x) }
  elsif v.key?("kvlistValue") then otlp_attributes(v["kvlistValue"]["values"])
  end
end
