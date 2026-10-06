package app.olauncher.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
A named group of apps that sits in a home screen slot and unfolds in place when tapped.
Stored in Prefs as JSON.
*/

data class Folder(
    val id: String,
    val name: String,
    val apps: List<FolderApp>,
) {
    val isFull get() = apps.size >= MAX_APPS

    fun contains(app: AppModel) = apps.any { it.key == FolderApp.keyOf(app) }

    fun toggle(app: AppModel.App): Folder =
        if (contains(app)) copy(apps = apps.filterNot { it.key == FolderApp.keyOf(app) })
        else copy(apps = apps + FolderApp.from(app))

    companion object {
        const val MAX_APPS = 8

        fun create(name: String) = Folder(UUID.randomUUID().toString(), name, emptyList())

        fun listFromJson(json: String): List<Folder> {
            if (json.isBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                List(array.length()) { i ->
                    val folder = array.getJSONObject(i)
                    val apps = folder.getJSONArray("apps")
                    Folder(
                        id = folder.getString("id"),
                        name = folder.getString("name"),
                        apps = List(apps.length()) { j ->
                            val app = apps.getJSONObject(j)
                            FolderApp(
                                label = app.getString("label"),
                                packageName = app.getString("package"),
                                activityClassName = app.optString("activity").ifEmpty { null },
                                user = app.getString("user"),
                            )
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        fun listToJson(folders: List<Folder>): String {
            val array = JSONArray()
            folders.forEach { folder ->
                val apps = JSONArray()
                folder.apps.forEach { app ->
                    apps.put(
                        JSONObject()
                            .put("label", app.label)
                            .put("package", app.packageName)
                            .put("activity", app.activityClassName ?: "")
                            .put("user", app.user)
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", folder.id)
                        .put("name", folder.name)
                        .put("apps", apps)
                )
            }
            return array.toString()
        }
    }
}

data class FolderApp(
    val label: String,
    val packageName: String,
    val activityClassName: String?,
    val user: String,
) {
    val key: String get() = "$packageName|$user"

    companion object {
        fun keyOf(app: AppModel) = "${app.appPackage}|${app.user}"

        fun from(app: AppModel.App) = FolderApp(
            label = app.appLabel,
            packageName = app.appPackage,
            activityClassName = app.activityClassName,
            user = app.user.toString(),
        )
    }
}
