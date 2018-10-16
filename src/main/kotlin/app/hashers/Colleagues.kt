// Copyright 2018 Sourcerer Inc. All Rights Reserved.
// Author: Liubov Yaronskaya (lyaronskaya@sourcerer.io)

package app.hashers

import app.FactCodes
import app.api.Api
import app.model.Author
import app.model.Fact
import app.model.Repo
import java.io.File
import kotlin.streams.toList
import org.eclipse.jgit.util.GitDateParser
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class LogBasedColleagues(private val serverRepo: Repo,
                         private val repoPath: String,
                         private val emails: HashSet<String>,
                         private val userEmails: HashSet<String>) {
    fun getLog(filePath: String, repoPath1: String): String {
        val builder = ProcessBuilder()
        builder.directory(File(repoPath1))
        builder.command(listOf("git", "log", "-p", "-M", "--follow",
                "--date=iso", "--", filePath))
        val process = builder.start()
        val log = process.inputStream.bufferedReader().lines().toList()
                .joinToString("\n")
        process.waitFor()
        return log
    }

    fun getAuthorEmail(line: String): String {
        val startIdx = line.indexOf("<") + 1
        val endIdx = line.indexOf(">")
        return line.substring(startIdx, endIdx)
    }

    fun getAuthorTime(line: String): Date {
        val date = line.substring(8)
        return GitDateParser.parse(date, Calendar.getInstance())
    }

    fun findLast(line: String, substring: String): Int {
        val substringRegex = Regex(substring)
        val result = substringRegex.findAll(line)
        if (result.toList().isEmpty()) {
            println(line)
            println(substring)
            return -1
        }
        val idx = result.last().range.first
        return idx
    }

    fun getLineAuthor(line: String, log: String): Pair<String, Date>? {
        val addedIdx = findLast(log, """\+$line""")
        if (addedIdx < 0) {
            println("Error in <$line>")
            return null
        }
        val authorLineIdx = findLast(log.substring(0, addedIdx), "Author: ")

        val authorEmail = getAuthorEmail(log.substring(authorLineIdx))
        val authorTime = getAuthorTime(log.substring(authorLineIdx).split
        ("\n")[1])
        return Pair(authorEmail, authorTime)
    }

    fun changedBy(line: String, logPiece: String): Pair<String, Date> {
        val deletedIdx = findLast(logPiece, "-$line")
        val changeAuthorIdx = findLast(logPiece.substring(0, deletedIdx),
                "Author: ")

        val authorEmail = getAuthorEmail(logPiece.substring(changeAuthorIdx))
        val authorTime = getAuthorTime(logPiece.substring(changeAuthorIdx)
                .split("\n")[1])
        return Pair(authorEmail, authorTime)
    }

    fun onFile(log: String, email: String, commitFrequency: Int):
            HashMap<String, Int> {
        val author2num = HashMap<String, Int>()
        emails.forEach { author2num[it] = 0 }

        log.split("commit ").forEach {
            rawCommit ->
                val rawList = rawCommit.split("\n")
                if (rawCommit.isNotEmpty() && rawList.size >= 3) {
                    val sha = rawList[0]
                    val author = rawList[1]
                    val date = rawList[2]
                    val data = rawList.subList(3, rawList.size)
                    val authorEmail = getAuthorEmail(author)
                    if (authorEmail == email) {
                        val commitTime = getAuthorTime(date)
                        val myDeleted = data.filter { it.startsWith("-") &&
                                !it.startsWith("--") }
                        myDeleted.forEach { line ->
                            if (line.substring(1).isNotBlank() && line.length
                                    > 3) {
                                println(line)
                                val result = getLineAuthor(line.substring(1),
                                        log)
                                if (result != null) {
                                    val res = TimeUnit.DAYS.convert(commitTime
                                            .time - result.second.time, TimeUnit.MILLISECONDS)
                                    print(res)
                                    if (res < commitFrequency) {
                                        author2num[result.first] =
                                                author2num[result.first]!! + 1
                                    }
                                }
                            }
                        }
                        // TODO(lyaronskaya): count changed_by authors.
                    }
                }
        }
        return author2num
    }

    fun getColleagues(): HashMap<String, Double> {
        val output = HashMap<String, Double>()
        emails.forEach { output[it] = 0.0 }
        File(repoPath).walk().forEach { file ->
            if (file.isFile) {
                val log = getLog(file.absolutePath, repoPath)
                userEmails.forEach { userEmail ->
                    // TODO(lyaronskaya): explain commitFrequency value.
                    val fileColleagues = onFile(log, userEmail, 120)
                    fileColleagues.forEach { email, value ->
                        output[email] = output[email]!! + value
                    }
                }
            }
        }
        return output
    }

    fun calculateAndSendFacts(api: Api) {
        val author2num = getColleagues()
        val stats = mutableListOf<Fact>()
        val author = Author(email = userEmails.toList()[0])
        author2num.forEach {anotherEmail, value ->
            if (anotherEmail !in userEmails) {
                // TODO(lyaronskaya): send as AuthorDistance after testing.
                stats.add(Fact(serverRepo, FactCodes.COLLEAGUES, value =
                anotherEmail, value2 = value.toString(), author = author))
            }
        }
        api.postFacts(stats).onErrorThrow()
    }
}
