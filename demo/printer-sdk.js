/**
 * Local Hardware Bridge - Server Mode Printer SDK
 *
 * This SDK is loaded by the **public/remote website** (in the user's browser).
 * It lets the website print through the Local Hardware Bridge running on the
 * user's local network / machine — **the user does not install anything**.
 *
 * Required setup:
 *   - The Local Hardware Bridge Server must be running on the user's network
 *     (e.g. a small PC, an on-prem server, the office workstation).
 *   - The website knows the bridge base URL (http://bridge-ip:12212).
 *   - CORS is enabled by default in the bridge.
 *
 * Typical flow:
 *   1. LHB.listPrinters(serverUrl)          -> ask bridge for OS printers
 *   2. LHB.listMappings(serverUrl)            -> see configured type->printer mappings
 *   3. LHB.choosePrinter(serverUrl, type, printerName) -> persist mapping on bridge
 *   4. LHB.print(serverUrl, document)         -> submit job
 *   5. LHB.connect(serverUrl, callbacks)      -> WebSocket for print status
 */
(function (global, factory) {
    typeof exports === 'object' && typeof module !== 'undefined' ? module.exports = factory() :
    typeof define === 'function' && define.amd ? define(factory) :
    (global = typeof globalThis !== 'undefined' ? globalThis : global || self, global.LHB = factory());
}(this, (function () { 'use strict';

    function getStorageKey(serverUrl, type) {
        return 'lhb.printer.' + serverUrl + '.' + type;
    }

    function request(method, url, body, token) {
        return new Promise(function (resolve, reject) {
            var xhr = new XMLHttpRequest();
            xhr.open(method, url, true);
            xhr.setRequestHeader('Content-Type', 'application/json');
            if (token) xhr.setRequestHeader('Authorization', 'Bearer ' + token);
            xhr.onreadystatechange = function () {
                if (xhr.readyState !== 4) return;
                if (xhr.status >= 200 && xhr.status < 300) {
                    try { resolve(xhr.response ? JSON.parse(xhr.response) : null); }
                    catch (e) { resolve(xhr.response); }
                } else {
                    reject(new Error('HTTP ' + xhr.status + ': ' + xhr.responseText));
                }
            };
            xhr.onerror = function () { reject(new Error('Network error: ' + url)); };
            xhr.send(body ? JSON.stringify(body) : null);
        });
    }

    function PrinterSDK() {}

    PrinterSDK.prototype.listPrinters = function (serverUrl, token) {
        return request('GET', serverUrl + '/system/printers.json', null, token);
    };

    PrinterSDK.prototype.listMappings = function (serverUrl, token) {
        return request('GET', serverUrl + '/printer/mappings', null, token);
    };

    PrinterSDK.prototype.choosePrinter = function (serverUrl, type, printerName, token) {
        var self = this;
        return request('POST', serverUrl + '/printer/mappings', { type: type, name: printerName }, token)
            .then(function () {
                try { localStorage.setItem(getStorageKey(serverUrl, type), printerName); } catch (e) {}
                return self.listMappings(serverUrl, token);
            });
    };

    PrinterSDK.prototype.getSavedPrinter = function (serverUrl, type) {
        try { return localStorage.getItem(getStorageKey(serverUrl, type)); } catch (e) { return null; }
    };

    PrinterSDK.prototype.print = function (serverUrl, document, token) {
        return request('POST', serverUrl + '/printer', document, token);
    };

    PrinterSDK.prototype.connect = function (serverUrl, callbacks, token) {
        callbacks = callbacks || {};
        var wsUrl = serverUrl.replace(/^http/, 'ws') + '/printer';
        if (token) wsUrl += '?token=' + encodeURIComponent(token);
        var ws = new WebSocket(wsUrl);
        ws.onopen = function () { if (callbacks.onConnect) callbacks.onConnect(ws); };
        ws.onclose = function () { if (callbacks.onDisconnect) callbacks.onDisconnect(); };
        ws.onerror = function (e) { if (callbacks.onError) callbacks.onError(e); };
        ws.onmessage = function (evt) {
            var data;
            try { data = JSON.parse(evt.data); } catch (e) { data = evt.data; }
            if (callbacks.onUpdate) callbacks.onUpdate(data);
        };
        return ws;
    };

    /**
     * Ensures a printer is mapped for the given type.
     * If a mapping already exists, use it.
     * If the user has a saved choice in localStorage, apply it.
     * Otherwise, call uiCallbacks.prompt(printers) -> promise<string> chosen printer.
     */
    PrinterSDK.prototype.ensurePrinter = function (serverUrl, type, token, uiCallbacks) {
        var self = this;
        uiCallbacks = uiCallbacks || {};
        return this.listMappings(serverUrl, token).then(function (mappings) {
            var existing = mappings.find(function (m) { return m.type === type; });
            if (existing && existing.name) return existing;
            var saved = self.getSavedPrinter(serverUrl, type);
            if (saved) return self.choosePrinter(serverUrl, type, saved, token);
            if (uiCallbacks.prompt) {
                return self.listPrinters(serverUrl, token).then(function (printers) {
                    return uiCallbacks.prompt(printers);
                }).then(function (chosen) {
                    if (!chosen) throw new Error('No printer selected');
                    return self.choosePrinter(serverUrl, type, chosen, token);
                });
            }
            throw new Error('No printer configured for type ' + type);
        });
    };

    return new PrinterSDK();
}));
