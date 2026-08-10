import {LitElement, html, css} from 'lit';
import {JsonRpc} from 'jsonrpc';
import '@vaadin/grid';

export class QwcServletFilters extends LitElement {

    jsonRpc = new JsonRpc(this);

    static properties = {
        _filters: {type: Array, state: true}
    };

    static styles = css`
        :host {
            display: flex;
            flex-direction: column;
            height: 100%;
            padding: 10px;
        }
    `;

    constructor() {
        super();
        this._filters = [];
    }

    connectedCallback() {
        super.connectedCallback();
        this.jsonRpc.getFilters().then(response => {
            this._filters = response.result;
        });
    }

    render() {
        return html`
            <vaadin-grid .items="${this._filters}" theme="row-stripes">
                <vaadin-grid-column path="name" header="Name" auto-width></vaadin-grid-column>
                <vaadin-grid-column path="className" header="Class" auto-width></vaadin-grid-column>
                <vaadin-grid-column path="priority" header="Priority" auto-width></vaadin-grid-column>
            </vaadin-grid>
        `;
    }
}

customElements.define('qwc-servlet-filters', QwcServletFilters);
