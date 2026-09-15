const Imap = require('node-imap');
const simpleParser = require("mailparser").simpleParser;
const { setRedisKey, getRedisKey } = require('./RedisService');
const { autoAgent } = require('./ProxyService');
const { classifyOAuthResponse } = require('../utils/tokenError');

const MICROSOFT_TOKEN_URL = 'https://login.microsoftonline.com/consumers/oauth2/v2.0/token';
const GRAPH_MAIL_SCOPE = 'https://graph.microsoft.com/Mail.Read https://graph.microsoft.com/Mail.ReadWrite offline_access';

const requestMicrosoftAccessToken = async ({
    refresh_token,
    client_id,
    email,
    socks5,
    http,
    scope = '',
    redisKeyPrefix,
    forceRefresh = false,
}) => {
    const redis_name = `${redisKeyPrefix}_${email}_access_token`;

    if (!forceRefresh) {
        const redis_result = await getRedisKey(redis_name);
        if (redis_result) {
            return {
                access_token: redis_result,
                fromCache: true,
            };
        }
    }

    const agentOptions = autoAgent(socks5, http);
    const bodyParams = {
        client_id,
        grant_type: 'refresh_token',
        refresh_token,
    };
    if (scope) {
        bodyParams.scope = scope;
    }

    const response = await agentOptions.fetch(MICROSOFT_TOKEN_URL, {
        method: 'POST',
        ...agentOptions.proxy,
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: new URLSearchParams(bodyParams).toString(),
    });

    const responseText = await response.text();

    if (!response.ok) {
        const classification = classifyOAuthResponse(responseText, response.status);
        const err = new Error(`HTTP error! status: ${response.status}, response: ${responseText}`);
        err.tokenClassification = classification;
        throw err;
    }

    let data;
    try {
        data = JSON.parse(responseText);
    } catch (parseError) {
        const err = new Error(`Failed to parse JSON: ${parseError.message}, response: ${responseText}`);
        err.tokenClassification = classifyOAuthResponse(responseText, response.status);
        throw err;
    }

    if (!data.access_token) {
        const err = new Error(`No access_token in token response: ${responseText}`);
        err.tokenClassification = classifyOAuthResponse(responseText, response.status);
        throw err;
    }

    await setRedisKey(redis_name, data.access_token, (data.expires_in || 3600) - 60);

    return {
        access_token: data.access_token,
        refresh_token: data.refresh_token || '',
        expires_in: data.expires_in || 3600,
        scope: data.scope || '',
        fromCache: false,
    };
};

const refreshAccessToken = async (refresh_token, client_id, email, socks5, http, options = {}) => {
    const result = await requestMicrosoftAccessToken({
        refresh_token,
        client_id,
        email,
        socks5,
        http,
        scope: GRAPH_MAIL_SCOPE,
        redisKeyPrefix: 'graph_api_result',
        forceRefresh: options.forceRefresh === true,
    });

    return {
        ...result,
        status: String(result.scope || '').indexOf('https://graph.microsoft.com/Mail.Read') !== -1,
    };
};

// IMAP 文件夹名 → Graph API well-known folder 名
const MAILBOX_MAP = {
    'INBOX':    'inbox',
    'Junk':     'junkemail',
    'Sent':     'sentitems',
    'Drafts':   'drafts',
    'Deleted':  'deleteditems',
    'Archive':  'archive',
};

const use_graph_api = async (refresh_token, client_id, mailbox, email, socks5, http) => {
    const temp_mailbox = MAILBOX_MAP[mailbox] || 'inbox';
    const tokenResult = await requestMicrosoftAccessToken({
        refresh_token,
        client_id,
        email,
        socks5,
        http,
        scope: GRAPH_MAIL_SCOPE,
        redisKeyPrefix: 'graph_api_result',
    });

    return {
        access_token: tokenResult.access_token,
        status: String(tokenResult.scope || '').indexOf('https://graph.microsoft.com/Mail.Read') !== -1,
        mailbox: temp_mailbox,
        refresh_token: tokenResult.refresh_token || '',
        expires_in: tokenResult.expires_in || 3600,
    };
}

const resolveMailTop = (top) => {
    if (top === 0 || top === '0' || String(top || '').toLowerCase() === 'all') {
        return 0;
    }

    const value = Number(top);
    return Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

const resolveContentMode = (options = {}) => {
    const mode = String(options.contentMode || '').trim().toLowerCase();
    if (['none', 'preview', 'full'].includes(mode)) return mode;
    return options.includeContent ? 'full' : 'none';
}

const use_get_graph_emails = async (graph_api_result, top = 0, email, socks5, http, options = {}) => {

    const agentOptions = autoAgent(socks5, http);
    const contentMode = resolveContentMode(options);
    const includePreview = contentMode === 'preview' || contentMode === 'full';
    const includeBody = contentMode === 'full';
    const requestedTop = resolveMailTop(top);
    const pageSize = requestedTop > 0 ? Math.min(requestedTop, 999) : 999;
    const emails = [];
    const selectFields = includeBody
        ? 'id,from,receivedDateTime,createdDateTime,sentDateTime,subject,bodyPreview,body'
        : includePreview
            ? 'id,from,receivedDateTime,createdDateTime,sentDateTime,subject,bodyPreview'
            : 'id,from,receivedDateTime,createdDateTime,sentDateTime';
    let requestUrl = `https://graph.microsoft.com/v1.0/me/mailFolders/${graph_api_result.mailbox}/messages?$top=${pageSize}&$select=${selectFields}&$orderby=receivedDateTime%20desc`;

    while (requestUrl && (requestedTop === 0 || emails.length < requestedTop)) {
        const response = await agentOptions.fetch(requestUrl, {
            method: 'GET',
            ...agentOptions.proxy,
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                "Authorization": `Bearer ${graph_api_result.access_token}`
            },
        });

        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`HTTP error! status: ${response.status}, response: ${errorText}`);
        }

        const responseData = await response.json();
        const pageEmails = Array.isArray(responseData.value) ? responseData.value : [];

        if (requestedTop > 0) {
            emails.push(...pageEmails.slice(0, requestedTop - emails.length));
        } else {
            emails.push(...pageEmails);
        }

        requestUrl = requestedTop === 0 || emails.length < requestedTop
            ? responseData['@odata.nextLink']
            : null;
    }

    const response_emails = emails.map(item => {
        return {
            id: item['id'],
            send: item['from']?.['emailAddress']?.['name']
                ? `"${item['from']['emailAddress']['name']}" <${item['from']['emailAddress']['address']}>`
                : item['from']?.['emailAddress']?.['address'] || '',
            subject: includePreview ? item['subject'] || '' : '',
            text: includePreview ? item['bodyPreview'] || '' : '',
            html: includeBody ? item['body']?.['content'] || '' : '',
            date: item['receivedDateTime'] || item['createdDateTime'] || item['sentDateTime'],
        }
    })

    return response_emails

}

const mapGraphMessage = (item, contentMode = 'none') => {
    const includePreview = contentMode === 'preview' || contentMode === 'full';
    const includeBody = contentMode === 'full';
    return {
        id: item['id'],
        send: item['from']?.['emailAddress']?.['name']
            ? `"${item['from']['emailAddress']['name']}" <${item['from']['emailAddress']['address']}>`
            : item['from']?.['emailAddress']?.['address'] || '',
        subject: includePreview ? item['subject'] || '' : '',
        text: includePreview ? item['bodyPreview'] || '' : '',
        html: includeBody ? item['body']?.['content'] || '' : '',
        date: item['receivedDateTime'] || item['createdDateTime'] || item['sentDateTime'],
    };
}

const use_get_graph_email_by_id = async (graph_api_result, messageId, email, socks5, http, options = {}) => {
    if (!messageId) return null;

    const agentOptions = autoAgent(socks5, http);
    const contentMode = resolveContentMode(options);
    const selectFields = 'id,from,receivedDateTime,createdDateTime,sentDateTime,subject,bodyPreview,body';
    const requestUrl = `https://graph.microsoft.com/v1.0/me/mailFolders/${graph_api_result.mailbox}/messages/${encodeURIComponent(messageId)}?$select=${selectFields}`;
    const response = await agentOptions.fetch(requestUrl, {
        method: 'GET',
        ...agentOptions.proxy,
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'Authorization': `Bearer ${graph_api_result.access_token}`,
            'Prefer': 'outlook.body-content-type="html"',
        },
    });

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP error! status: ${response.status}, response: ${errorText}`);
    }

    const item = await response.json();
    return mapGraphMessage(item, contentMode);
}

const use_imap_api = async (refresh_token, client_id, email, socks5, http) => {
    const tokenResult = await requestMicrosoftAccessToken({
        refresh_token,
        client_id,
        email,
        socks5,
        http,
        redisKeyPrefix: 'imap_api_result',
    });

    return {
        access_token: tokenResult.access_token,
        refresh_token: tokenResult.refresh_token || '',
        expires_in: tokenResult.expires_in || 3600,
    };
}

const generateAuthString = (email, accessToken) => {
    const authString = `user=${email}\x01auth=Bearer ${accessToken}\x01\x01`;
    return Buffer.from(authString).toString('base64');
}

const use_get_imap_emails = (email, authString, mailbox = "INBOX", top = 0, options = {}) => {
    return new Promise((resolve, reject) => {
        const contentMode = resolveContentMode(options);
        const includePreview = contentMode === 'preview' || contentMode === 'full';
        const includeBody = contentMode === 'full';
        const imap = new Imap({
            user: email,
            xoauth2: authString,
            host: 'outlook.office365.com',
            port: 993,
            tls: true,
            tlsOptions: {
                rejectUnauthorized: false
            }
        });
        const emailList = [];
        let messageCount = 0;
        let processedCount = 0;

        imap.once("ready", async () => {
            try {
                // 动态打开指定的邮箱（如 INBOX 或 Junk）
                await new Promise((resolve, reject) => {
                    imap.openBox(mailbox, true, (err, box) => {
                        if (err) return reject(err);
                        resolve(box);
                    });
                });

                const results = await new Promise((resolve, reject) => {
                    imap.search(["ALL"], (err, results) => {
                        if (err) return reject(err);

                        const requestedTop = resolveMailTop(top);

                        if (requestedTop === 0) {
                            resolve(results);
                            return;
                        }

                        const temp_top = Math.min(requestedTop, results.length);
                        resolve(results.slice(-temp_top));

                    });
                });

                if (results.length === 0) {
                    resolve([]);
                    imap.end();
                    return;
                }

                messageCount = results.length;
                const headerFields = includePreview
                    ? "HEADER.FIELDS (FROM DATE MESSAGE-ID SUBJECT)"
                    : "HEADER.FIELDS (FROM DATE MESSAGE-ID)";
                const f = imap.fetch(results, { bodies: includeBody ? '' : headerFields });

                f.on("message", (msg, seqno) => {
                    let messageAttrs = null;
                    let parsedMail = null;
                    let completed = false;

                    const finishMessage = () => {
                        if (completed || !parsedMail) return;
                        completed = true;

                        const stableId = parsedMail.messageId
                            || (messageAttrs?.uid ? `${mailbox}:${messageAttrs.uid}` : `${mailbox}:${seqno}`);

                        const data = {
                            id: stableId,
                            uid: messageAttrs?.uid || null,
                            seqno,
                            send: parsedMail.from?.text || '',
                            subject: includePreview ? parsedMail.subject || '' : '',
                            text: includeBody ? parsedMail.text || '' : '',
                            html: includeBody ? parsedMail.html || '' : '',
                            date: parsedMail.date,
                        };
                        emailList.push(data);
                    };

                    const completeMessage = () => {
                        processedCount++;
                        // 当所有邮件都处理完成后关闭连接
                        if (processedCount === messageCount) {
                            imap.end();
                        }
                    };

                    msg.on("body", (stream, info) => {
                        // 使用 Promise 包装 simpleParser 以确保所有邮件都被处理完成
                        simpleParser(stream)
                            .then(mail => {
                                parsedMail = mail;
                            })
                            .catch(err => {
                                console.error('Error parsing email:', err);
                            })
                            .finally(() => {
                                finishMessage();
                                completeMessage();
                            });
                    });

                    msg.once("attributes", (attrs) => {
                        messageAttrs = attrs || null;
                    });
                });

                f.once("error", (err) => {
                    console.error('IMAP fetch error:', err);
                    reject(err);
                    imap.end();
                });
            } catch (err) {
                console.error('IMAP ready error:', err);
                reject(err);
                imap.end();
            }
        });

        imap.once('error', (err) => {
            console.error('IMAP connection error:', err);
            reject(err);
        });

        imap.once('end', () => {
            // 按日期降序排序，确保最新邮件在前
            emailList.sort((a, b) => new Date(b.date) - new Date(a.date));
            resolve(emailList);
            console.log('IMAP connection ended');
        });

        imap.connect();
    })
}

const parseImapMessage = (email, authString, mailbox = "INBOX", searchCriteria = [], directUid = 0, directSeqno = 0) => {
    return new Promise((resolve, reject) => {
        const imap = new Imap({
            user: email,
            xoauth2: authString,
            host: 'outlook.office365.com',
            port: 993,
            tls: true,
            tlsOptions: {
                rejectUnauthorized: false
            }
        });

        let resolved = false;
        const done = (err, value = null) => {
            if (resolved) return;
            resolved = true;
            try { imap.end(); } catch (_) {}
            err ? reject(err) : resolve(value);
        };

        imap.once("ready", async () => {
            try {
                await new Promise((resolveOpen, rejectOpen) => {
                    imap.openBox(mailbox, true, (err, box) => {
                        if (err) return rejectOpen(err);
                        resolveOpen(box);
                    });
                });

                const results = directSeqno
                    ? [directSeqno]
                    : directUid
                    ? [directUid]
                    : await new Promise((resolveSearch, rejectSearch) => {
                        imap.search(searchCriteria, (err, found) => {
                            if (err) return rejectSearch(err);
                            resolveSearch(found || []);
                        });
                    });

                if (!results.length) {
                    done(null, null);
                    return;
                }

                const uid = results[results.length - 1];
                const f = directSeqno
                    ? imap.seq.fetch([uid], { bodies: '' })
                    : imap.fetch([uid], { bodies: '' });
                let attrs = null;
                let completed = false;
                let messageSeen = false;
                let bodySeen = false;

                f.on("message", (msg, seqno) => {
                    messageSeen = true;
                    msg.once("attributes", (messageAttrs) => {
                        attrs = messageAttrs || null;
                    });

                    msg.on("body", (stream) => {
                        bodySeen = true;
                        simpleParser(stream)
                            .then((mail) => {
                                if (completed) return;
                                completed = true;
                                done(null, {
                                    id: mail.messageId || (attrs?.uid ? `${mailbox}:${attrs.uid}` : `${mailbox}:${seqno}`),
                                    uid: attrs?.uid || uid,
                                    seqno,
                                    send: mail.from?.text || '',
                                    subject: mail.subject || '',
                                    text: mail.text || '',
                                    html: mail.html || '',
                                    date: mail.date,
                                });
                            })
                            .catch(done);
                    });
                });

                f.once("error", done);
                f.once("end", () => {
                    if (!messageSeen || !bodySeen) done(null, null);
                });
            } catch (err) {
                done(err);
            }
        });

        imap.once('error', done);
        imap.connect();
    });
}

const use_get_imap_email_by_id = async (email, authString, mailbox = "INBOX", identifiers = {}) => {
    const seqno = Number(identifiers.seqno || 0);
    if (Number.isFinite(seqno) && seqno > 0) {
        const bySeqno = await parseImapMessage(email, authString, mailbox, [], 0, seqno);
        if (bySeqno) return bySeqno;
    }

    const uid = Number(identifiers.uid || 0);
    if (Number.isFinite(uid) && uid > 0) {
        const byUid = await parseImapMessage(email, authString, mailbox, [], uid);
        if (byUid) return byUid;
    }

    const messageId = String(identifiers.messageId || '').trim();
    if (messageId && !messageId.startsWith(`${mailbox}:`)) {
        return parseImapMessage(email, authString, mailbox, [['HEADER', 'MESSAGE-ID', messageId]]);
    }

    return null;
}

const process_mails = (email, authString, mailbox = "INBOX") => {
    return new Promise((resolve, reject) => {
        let settled = false;

        const safeResolve = (val) => { if (!settled) { settled = true; resolve(val); } };
        const safeReject = (err) => { if (!settled) { settled = true; reject(err); } };

        const imap = new Imap({
            user: email,
            xoauth2: authString,
            host: 'outlook.office365.com',
            port: 993,
            tls: true,
            tlsOptions: {
                rejectUnauthorized: false
            }
        });

        async function openInboxFolder() {
            return new Promise((resolve, reject) => {
                imap.openBox(mailbox, false, (err, box) => {
                    if (err) return reject(err);
                    resolve(box);
                });
            });
        }

        async function searchEmails() {
            return new Promise((resolve, reject) => {
                imap.search(['ALL'], (err, results) => {
                    if (err) return reject(err);
                    resolve(results);
                });
            });
        }

        async function markAsDeleted(uids) {
            return new Promise((resolve, reject) => {
                imap.addFlags(uids, ['\\Deleted'], (err) => {
                    if (err) return reject(err);
                    resolve();
                });
            });
        }

        async function expungeDeleted() {
            return new Promise((resolve, reject) => {
                imap.expunge((err) => {
                    if (err) return reject(err);
                    resolve();
                });
            });
        }

        imap.once('ready', async () => {
            try {
                await openInboxFolder();

                const results = await searchEmails();
                if (results.length === 0) {
                    console.log('No emails found in mailbox:', mailbox);
                    imap.end();
                    return;
                }

                const f = imap.fetch(results, { bodies: '' });
                const deletePromises = [];

                f.on('message', (msg, seqno) => {
                    console.log('Message #%d', seqno);
                    msg.on('attributes', (attrs) => {
                        const p = markAsDeleted([attrs.uid]).then(() => {
                            console.log('Marked as deleted:', seqno);
                        });
                        deletePromises.push(p);
                    });
                });

                f.once('error', (err) => {
                    console.log('Fetch error: ' + err);
                    safeReject(err);
                    imap.end();
                });

                f.once('end', async () => {
                    console.log('Done fetching all messages!');
                    await Promise.all(deletePromises);
                    await expungeDeleted();
                    console.log('Expunged deleted messages.');
                    imap.end();
                });
            } catch (err) {
                console.error('Error:', err);
                safeReject(err);
                imap.end();
            }
        });

        imap.once('error', (err) => {
            console.error('IMAP connection error:', err);
            safeReject(err);
        });

        imap.once('end', () => {
            console.log('Connection ended');
            safeResolve();
        });

        imap.connect();
    });
}

const use_test_proxy = async (socks5, http) => {

    const agentOptions = autoAgent(socks5, http);

    const response = await agentOptions.fetch('https://unix.xin/api/get_ip', {
        ...agentOptions.proxy,
    })

    const body = await response.json();

    return {
        ip: body.ip,
    };
}

const use_delete_graph_emails = async (graph_api_result, id, socks5, http) => {
    const agentOptions = autoAgent(socks5, http);
    const response = await agentOptions.fetch(`https://graph.microsoft.com/v1.0/me/messages/${id}`, {
        ...agentOptions.proxy,
        method: 'DELETE',
        headers: {
            'Authorization': `Bearer ${graph_api_result.access_token}`,
            'Content-Type': 'application/json',
        },
    });

    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`Failed to delete email ${id}: status ${response.status}, response: ${errorText}`);
    }

    console.log(`Deleted email with id: ${id}`);
}



module.exports = {
    requestMicrosoftAccessToken,
    refreshAccessToken,
    use_graph_api,
    use_get_graph_emails,
    use_get_graph_email_by_id,
    use_imap_api,
    generateAuthString,
    use_get_imap_emails,
    use_get_imap_email_by_id,
    process_mails,
    use_test_proxy,
    use_delete_graph_emails,
}
