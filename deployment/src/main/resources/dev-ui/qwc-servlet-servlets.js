import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';
import '@vaadin/grid';
import {columnBodyRenderer} from '@vaadin/grid/lit.js';
import '@qomponent/qui-badge';

export class QwcServletServlets extends LitElement {

    jsonRpc = new JsonRpc(this);

    static properties = {
        _servlets: {type: Array, state: true}
    };

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
            padding: 10px;
        }
        .mappings {
            display: flex;
            flex-wrap: wrap;
            gap: 4px;
        }
        .mapping {
            background: var(--lumo-contrast-10pct);
            border-radius: 4px;
            padding: 2px 6px;
            font-family: monospace;
            font-size: 0.85em;
        }
    `;

    constructor() {
        super();
        this._servlets = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this.jsonRpc.getServlets().then(response => {
            this._servlets = response.result;
        });
    }

    render() {
        return html`
            <vaadin-grid .items="${this._servlets}" theme="row-stripes">
                <vaadin-grid-column path="name" header="Name" auto-width></vaadin-grid-column>
                <vaadin-grid-column path="className" header="Class" auto-width></vaadin-grid-column>
                <vaadin-grid-column header="URL Patterns" auto-width
                    ${columnBodyRenderer(this._renderMappings, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Dispatch" auto-width
                    ${columnBodyRenderer(this._renderDispatch, [])}></vaadin-grid-column>
                <vaadin-grid-column header="Status" auto-width
                    ${columnBodyRenderer(this._renderStatus, [])}></vaadin-grid-column>
                <vaadin-grid-column path="loadOnStartup" header="Load Order" auto-width></vaadin-grid-column>
            </vaadin-grid>
        `;
    }

    _renderMappings(servlet) {
        const mappings = servlet.mappings || [];
        return html`<div class="mappings">${mappings.map(m => html`<span class="mapping">${m}</span>`)}</div>`;
    }

    _renderDispatch(servlet) {
        if (servlet.runOnVirtualThread) {
            return html`<qui-badge level="contrast"><span>Virtual Thread</span></qui-badge>`;
        }
        return html`<qui-badge level="success"><span>Event Loop</span></qui-badge>`;
    }

    _renderStatus(servlet) {
        if (servlet.initFailed) {
            return html`<qui-badge level="error"><span>Failed</span></qui-badge>`;
        }
        if (servlet.initialized) {
            return html`<qui-badge level="success"><span>Ready</span></qui-badge>`;
        }
        return html`<qui-badge><span>Pending</span></qui-badge>`;
    }
}

customElements.define('qwc-servlet-servlets', QwcServletServlets);
