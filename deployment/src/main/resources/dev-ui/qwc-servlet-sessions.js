import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';
import '@vaadin/grid';
import {columnBodyRenderer} from '@vaadin/grid/lit.js';
import '@vaadin/button';

export class QwcServletSessions extends LitElement {

    jsonRpc = new JsonRpc(this);

    static properties = {
        _sessions: {type: Array, state: true}
    };

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
            padding: 10px;
            gap: 10px;
        }
        .header {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .count {
            font-size: 1.2em;
            font-weight: bold;
        }
    `;

    constructor() {
        super();
        this._sessions = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this._refresh();
    }

    _refresh() {
        this.jsonRpc.getActiveSessions().then(response => {
            this._sessions = response.result;
        });
    }

    _invalidate(sessionId) {
        this.jsonRpc.invalidateSession({sessionId: sessionId}).then(() => {
            this._refresh();
        });
    }

    _formatTime(timestamp) {
        if (!timestamp) return '-';
        return new Date(timestamp).toLocaleString();
    }

    render() {
        return html`
            <div class="header">
                <span class="count">Active sessions: ${this._sessions.length}</span>
                <vaadin-button theme="small" @click="${this._refresh}">Refresh</vaadin-button>
            </div>
            <vaadin-grid .items="${this._sessions}" theme="row-stripes">
                <vaadin-grid-column path="id" header="Session ID" auto-width></vaadin-grid-column>
                <vaadin-grid-column header="Created" auto-width
                    ${columnBodyRenderer(s => html`${this._formatTime(s.creationTime)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Last Accessed" auto-width
                    ${columnBodyRenderer(s => html`${this._formatTime(s.lastAccessedTime)}`, [])}></vaadin-grid-column>
                <vaadin-grid-column path="maxInactiveInterval" header="Timeout (s)" auto-width></vaadin-grid-column>
                <vaadin-grid-column header="Actions" auto-width
                    ${columnBodyRenderer(s => html`
                        <vaadin-button theme="small error" @click="${() => this._invalidate(s.id)}">
                            Invalidate
                        </vaadin-button>
                    `, [])}></vaadin-grid-column>
            </vaadin-grid>
        `;
    }
}

customElements.define('qwc-servlet-sessions', QwcServletSessions);
