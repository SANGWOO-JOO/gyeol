{{- define "gyeol.labels" -}}
app.kubernetes.io/part-of: gyeol
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/* Java 서비스 공통 환경 변수 */}}
{{- define "gyeol.javaEnv" -}}
- name: DB_URL
  value: {{ .Values.db.url | quote }}
- name: DB_USER
  value: {{ .Values.db.user | quote }}
- name: DB_PASSWORD
  valueFrom: { secretKeyRef: { name: {{ .Values.secretName }}, key: DB_PASSWORD } }
- name: AWS_REGION
  value: {{ .Values.aws.region | quote }}
- name: UPLOAD_BUCKET
  value: {{ .Values.aws.uploadBucket | quote }}
{{- with .Values.aws.endpoint }}
- name: SPRING_CLOUD_AWS_ENDPOINT
  value: {{ . | quote }}
- name: SPRING_CLOUD_AWS_S3_PATHSTYLEACCESSENABLED
  value: "true"
{{- end }}
{{- if .Values.aws.localstack }}
- name: SPRING_CLOUD_AWS_CREDENTIALS_ACCESSKEY
  value: test
- name: SPRING_CLOUD_AWS_CREDENTIALS_SECRETKEY
  value: test
{{- end }}
{{- end }}

{{/* Spring Boot 상태 확인을 쿠버네티스 probe로. JVM 시작이 느려 startupProbe로 최대 2분 기다린다 */}}
{{- define "gyeol.springProbes" -}}
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 3
  failureThreshold: 40
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 10
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
  periodSeconds: 5
{{- end }}
