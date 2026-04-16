package com.annotation.recorder.storage

import android.util.Xml
import com.annotation.recorder.domain.EventSnapshot
import com.annotation.recorder.domain.SessionMeta
import com.annotation.recorder.domain.TreeNodeSnapshot
import com.annotation.recorder.domain.TreeSnapshot
import java.io.File
import java.io.FileOutputStream
import org.xmlpull.v1.XmlSerializer

class SessionXmlWriter {

    fun write(
        output: File,
        meta: SessionMeta,
        events: List<EventSnapshot>,
        snapshots: List<TreeSnapshot>,
        snapshotImagePaths: Map<String, String>,
        snapshotSemanticPaths: Map<String, String>
    ) {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { fos ->
            val serializer = Xml.newSerializer()
            serializer.setOutput(fos, "UTF-8")
            serializer.startDocument("UTF-8", true)

            serializer.startTag(null, "session")
            serializer.attribute(null, "id", safe(meta.sessionId))
            serializer.attribute(null, "appVersion", safe(meta.appVersion))
            serializer.attribute(null, "deviceModel", safe(meta.deviceModel))
            serializer.attribute(null, "sdkInt", meta.sdkInt.toString())
            serializer.attribute(null, "recordingMode", safe(meta.recordingMode))
            serializer.attribute(null, "pipelineVersion", safe(meta.pipelineVersion))
            serializer.attribute(null, "semanticStatus", safe(meta.semanticStatus))
            serializer.attribute(null, "videoPath", safe(meta.videoPath))
            serializer.attribute(null, "startedAtEpochMs", meta.startedAtEpochMs.toString())
            serializer.attribute(null, "endedAtEpochMs", (meta.endedAtEpochMs ?: 0L).toString())

            serializer.startTag(null, "events")
            events.forEach { event ->
                serializer.startTag(null, "event")
                serializer.attribute(null, "id", safe(event.id))
                serializer.attribute(null, "tsElapsedNanos", event.tsElapsedNanos.toString())
                serializer.attribute(null, "type", safe(event.type))
                serializer.attribute(null, "package", safe(event.packageName))
                serializer.attribute(null, "class", safe(event.className))
                serializer.attribute(null, "text", safe(event.textSummary))
                serializer.attribute(null, "sourceViewId", safe(event.sourceViewId))
                serializer.attribute(null, "bounds", safe(event.bounds))
                serializer.attribute(null, "coordSource", safe(event.coord.source))
                serializer.attribute(null, "x1", event.coord.x1.toString())
                serializer.attribute(null, "y1", event.coord.y1.toString())
                serializer.attribute(null, "x2", event.coord.x2.toString())
                serializer.attribute(null, "y2", event.coord.y2.toString())
                serializer.attribute(null, "snapshotId", safe(event.snapshotId))
                serializer.endTag(null, "event")
            }
            serializer.endTag(null, "events")

            serializer.startTag(null, "treeSnapshots")
            snapshots.forEach { snapshot ->
                serializer.startTag(null, "snapshot")
                serializer.attribute(null, "id", safe(snapshot.id))
                serializer.attribute(null, "tsElapsedNanos", snapshot.tsElapsedNanos.toString())
                serializer.attribute(null, "rootWindowId", snapshot.rootWindowId.toString())
                serializer.attribute(
                    null,
                    "imagePath",
                    safe(snapshotImagePaths[snapshot.id])
                )
                serializer.attribute(
                    null,
                    "semanticPath",
                    safe(snapshotSemanticPaths[snapshot.id])
                )
                snapshot.root?.let { writeNode(serializer, it) }
                serializer.endTag(null, "snapshot")
            }
            serializer.endTag(null, "treeSnapshots")

            serializer.startTag(null, "eventTreeLinks")
            events.forEach { event ->
                serializer.startTag(null, "link")
                serializer.attribute(null, "eventId", safe(event.id))
                serializer.attribute(null, "snapshotId", safe(event.snapshotId))
                serializer.endTag(null, "link")
            }
            serializer.endTag(null, "eventTreeLinks")

            serializer.endTag(null, "session")
            serializer.endDocument()
            serializer.flush()
        }
    }

    private fun writeNode(serializer: XmlSerializer, node: TreeNodeSnapshot) {
        serializer.startTag(null, "node")
        serializer.attribute(null, "nodeId", safe(node.nodeId))
        serializer.attribute(null, "className", safe(node.className))
        serializer.attribute(null, "text", safe(node.text))
        serializer.attribute(null, "contentDesc", safe(node.contentDesc))
        serializer.attribute(null, "viewIdRes", safe(node.viewIdRes))
        serializer.attribute(null, "bounds", safe(node.bounds))
        serializer.attribute(null, "clickable", node.clickable.toString())
        serializer.attribute(null, "scrollable", node.scrollable.toString())
        serializer.attribute(null, "enabled", node.enabled.toString())
        serializer.attribute(null, "focused", node.focused.toString())

        node.children.forEach { child -> writeNode(serializer, child) }
        serializer.endTag(null, "node")
    }

    private fun safe(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val builder = StringBuilder(value.length)
        value.forEach { ch ->
            if (isValidXmlChar(ch)) {
                builder.append(ch)
            } else {
                builder.append(' ')
            }
        }
        return builder.toString()
    }

    private fun isValidXmlChar(ch: Char): Boolean {
        val code = ch.code
        return code == 0x9 || code == 0xA || code == 0xD ||
            (code in 0x20..0xD7FF) || (code in 0xE000..0xFFFD)
    }
}
