package com.cocakova.pygmalion.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cocakova.pygmalion.app
import com.cocakova.pygmalion.core.api.ComfyClient
import com.cocakova.pygmalion.core.api.ServerEndpoint
import com.cocakova.pygmalion.data.AuthKind
import com.cocakova.pygmalion.data.Secrets
import com.cocakova.pygmalion.data.ServerEntity
import com.cocakova.pygmalion.net.AddressPolicy
import com.cocakova.pygmalion.net.Http
import com.cocakova.pygmalion.net.ServerSession
import com.cocakova.pygmalion.ui.CurrentServer
import com.cocakova.pygmalion.ui.theme.Atelier
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditor(existing: ServerEntity?, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var auth by remember { mutableStateOf(existing?.authKind ?: AuthKind.NONE) }
    var user by remember { mutableStateOf(existing?.authUser ?: "") }
    var secret by remember { mutableStateOf(existing?.let { app.secrets.get(Secrets.serverSecret(it.id)) } ?: "") }
    var allowInsecure by remember { mutableStateOf(existing?.allowInsecure ?: false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val normalized = AddressPolicy.normalize(url)
    val verdict = if (url.isBlank()) null else AddressPolicy.check(normalized)
    val canSave = url.isNotBlank() && verdict != AddressPolicy.Verdict.INVALID &&
        (verdict != AddressPolicy.Verdict.NEEDS_OPT_IN || allowInsecure)

    fun draft(id: String) = ServerEntity(
        id = id,
        name = name.ifBlank { runCatching { java.net.URI(normalized).host }.getOrNull() ?: "ComfyUI" },
        baseUrl = normalized,
        authKind = auth,
        authUser = user.ifBlank { null },
        clientId = existing?.clientId ?: UUID.randomUUID().toString(),
        allowInsecure = allowInsecure,
        powerJson = existing?.powerJson,
        sortOrder = existing?.sortOrder ?: 0,
        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
    )

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (existing == null) "Add a server" else "Edit server", style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(
                url, { url = it; result = null }, label = { Text("Address") },
                placeholder = { Text("192.168.1.20:8188 or https://comfy.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    when (verdict) {
                        AddressPolicy.Verdict.OK_TLS -> Text("Encrypted connection", color = Atelier.colors.done)
                        AddressPolicy.Verdict.OK_PRIVATE -> Text("Private network address")
                        AddressPolicy.Verdict.NEEDS_OPT_IN -> Text("Plain http to a public address: traffic would cross the internet unencrypted", color = Atelier.colors.error)
                        AddressPolicy.Verdict.INVALID -> Text("That doesn't look like an address", color = Atelier.colors.error)
                        null -> Text("Include the port if it isn't 80/443 — ComfyUI's default is 8188")
                    }
                },
            )
            if (verdict == AddressPolicy.Verdict.NEEDS_OPT_IN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowInsecure, { allowInsecure = it })
                    Text("I understand — use it anyway", style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Authentication", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AuthKind.NONE to "None", AuthKind.BEARER to "Token", AuthKind.BASIC to "Password", AuthKind.HEADER to "Header").forEach { (k, label) ->
                    FilterChip(selected = auth == k, onClick = { auth = k; result = null }, label = { Text(label) })
                }
            }
            when (auth) {
                AuthKind.NONE -> Text(
                    "For a server behind a reverse proxy, choose how the proxy expects you to sign in.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AuthKind.BASIC, AuthKind.HEADER -> OutlinedTextField(
                    user, { user = it }, label = { Text(if (auth == AuthKind.BASIC) "User name" else "Header name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                else -> Unit
            }
            if (auth != AuthKind.NONE) {
                OutlinedTextField(
                    secret, { secret = it }, label = { Text(if (auth == AuthKind.BASIC) "Password" else "Token") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Stored encrypted on this phone, never backed up") },
                )
            }

            result?.let { (ok, msg) ->
                Text(msg, color = if (ok) Atelier.colors.done else Atelier.colors.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = canSave && !testing,
                    onClick = {
                        testing = true; result = null
                        scope.launch {
                            val s = draft("test")
                            val c = ComfyClient(Http.ktor, ServerEndpoint(s.baseUrl, ServerSession.authHeaders(s, secret.ifBlank { null })))
                            result = runCatching { c.systemStats() }.fold(
                                { st -> true to ("Connected · ComfyUI ${st.comfyuiVersion ?: "?"}" + (st.devices.firstOrNull()?.name?.let { " · ${it.substringBefore(" : ").take(40)}" } ?: "")) },
                                { e -> false to "Couldn't connect: ${e.message ?: e::class.simpleName}" },
                            )
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test") }
                Spacer(Modifier.weight(1f))
                if (existing != null) TextButton(onClick = {
                    scope.launch {
                        app.db.servers().delete(existing.id)
                        app.secrets.remove("server:${existing.id}:")
                        app.sessions.invalidate(existing.id)
                        onDismiss()
                    }
                }) { Text("Remove", color = Atelier.colors.error) }
                Button(enabled = canSave, onClick = {
                    scope.launch {
                        val s = draft(existing?.id ?: UUID.randomUUID().toString())
                        app.secrets.put(Secrets.serverSecret(s.id), secret.takeIf { auth != AuthKind.NONE })
                        app.db.servers().upsert(s)
                        app.sessions.invalidate(s.id)
                        if (existing == null) CurrentServer.select(s.id)
                        onDismiss()
                    }
                }) { Text("Save") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
