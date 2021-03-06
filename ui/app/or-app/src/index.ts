import {
    css,
    customElement,
    html,
    LitElement,
    property,
    PropertyValues,
    query,
    TemplateResult,
    unsafeCSS
} from "lit-element";
import {unsafeHTML} from "lit-html/directives/unsafe-html";
import {AppConfig, RealmAppConfig, router} from "./types";
import "@openremote/or-translate";
import "@openremote/or-mwc-components/dist/or-mwc-menu";
import "@openremote/or-mwc-components/dist/or-mwc-snackbar";
import "./or-header";
import "@openremote/or-icon";
import {updateMetadata} from "pwa-helpers/metadata";
import i18next from "i18next";
import manager, {Auth, DefaultColor2, DefaultColor3, ManagerConfig, Util, BasicLoginResult} from "@openremote/core";
import {DEFAULT_LANGUAGES, HeaderConfig, HeaderItem, Languages} from "./or-header";
import {DialogConfig, OrMwcDialog, showErrorDialog, showDialog} from "@openremote/or-mwc-components/dist/or-mwc-dialog";
import {OrMwcSnackbar} from "@openremote/or-mwc-components/dist/or-mwc-snackbar";
import {AnyAction, EnhancedStore, Unsubscribe} from "@reduxjs/toolkit";
import {ThunkMiddleware} from "redux-thunk";
import {AppStateKeyed, updatePage} from "./app";
import { InputType, OrInputChangedEvent } from "@openremote/or-input";

export * from "./app";
export * from "./or-header";
export * from "./types";

// Declare MANAGER_URL - Global var injected by webpack
declare var MANAGER_URL: string;

export {HeaderConfig};

export function getRealmQueryParameter(): string | undefined {
    if(location.search && location.search !== "") {
        return Util.getQueryParameter(location.search, "realm");
    }

    if(location.hash) {
        const index = location.hash.indexOf("?");
        if(index > -1) {
            return Util.getQueryParameter(location.hash.substring(index + 1), "realm");
        }
    }
}

const DEFAULT_MANAGER_CONFIG: ManagerConfig = {
    managerUrl: MANAGER_URL,
    auth: Auth.KEYCLOAK,
    autoLogin: true,
    realm: getRealmQueryParameter() || "master",
    consoleAutoEnable: true,
    loadTranslations: ["app", "or"]
};

@customElement("or-app")
export class OrApp<S extends AppStateKeyed> extends LitElement {

    @property({type: Object})
    public appConfig!: AppConfig<S>;

    @property({type: Object})
    public managerConfig?: ManagerConfig;

    @query("main")
    protected _mainElem!: HTMLElement;

    @property()
    protected _initialised = false;

    @property()
    protected _page?: string;

    protected _config!: RealmAppConfig;

    protected _store: EnhancedStore<S, AnyAction, ReadonlyArray<ThunkMiddleware<S>>>;
    protected _storeUnsubscribe!: Unsubscribe;

    // language=CSS
    static get styles() {
        return css`
            :host {
                --or-app-color2: ${unsafeCSS(DefaultColor2)};
                --or-app-color3: #22211f;
                --or-app-color4: #4D9D2A;
                --or-console-primary-color: #4D9D2A;
                color: ${unsafeCSS(DefaultColor3)};
                fill: ${unsafeCSS(DefaultColor3)};
                font-size: 14px;

                height: 100vh;
                display: flex;
                flex: 1;
                flex-direction: column;
            }
                
            .main-content {
                display: flex;
                flex: 1;
                box-sizing: border-box;
                background-color: var(--or-app-color2);
                overflow: auto;
            }
            
            main > * {
                display: flex;
                flex: 1;
                position: relative;
            }
    
            .desktop-hidden {
                display: none !important;
            }
            
            @media only screen and (max-width: 780px) {
                .desktop-hidden {
                    display: inline-block !important;
                }
            }
            
            /* HEADER STYLES */
            or-header a > or-icon {
                margin-right: 10px;
            }
        `;
    }

    constructor(store: EnhancedStore<S, AnyAction, ReadonlyArray<ThunkMiddleware<S>>>) {
        super();
        this._store = store;

        // Set this element as the host for dialogs
        OrMwcDialog.DialogHostElement = this;
        OrMwcSnackbar.DialogHostElement = this;
    }

    connectedCallback() {
        super.connectedCallback();
        this._storeUnsubscribe = this._store.subscribe(() => this.stateChanged(this._store.getState()));
        this.stateChanged(this._store.getState());
    }

    disconnectedCallback() {
        this._storeUnsubscribe();
        super.disconnectedCallback();
    }

    protected firstUpdated(_changedProperties: Map<PropertyKey, unknown>): void {
        super.firstUpdated(_changedProperties);

        if (!this.appConfig) {
            console.error("No AppConfig supplied");
            return;
        }

        if (!this._store) {
            console.error("No Redux store supplied");
            return;
        }

        if (!this.appConfig.pages || Object.keys(this.appConfig.pages).length === 0) {
            console.error("No page providers");
            return;
        }

        const realm = getRealmQueryParameter();
        const config = this._getConfig(realm);

        if (!config) {
            console.error("No default AppConfig or realm specific config for requested realm: " + realm);
            return;
        } else {
            this._config = config;
        }

        const managerConfig = this.managerConfig || DEFAULT_MANAGER_CONFIG;
        managerConfig.basicLoginProvider = (u, p) => this.doBasicLogin(u, p);

        console.info("Initialising the manager");

        manager.init(managerConfig).then((success) => {
            if (success) {
                this._initialised = true;

                this.appConfig.pages.forEach((pageProvider, index) => {
                    if (pageProvider.routes) {
                        pageProvider.routes.forEach((route) => {
                            router.on(
                                route, (params, query) => {
                                    this._store.dispatch(updatePage({page: pageProvider.name, params: params}));
                                }
                            );
                        });
                    }
                });

                if (this.appConfig.pages.length > 0) {
                    router.on("*", (params, query) => {
                        this._store.dispatch(updatePage(this.appConfig.pages[0].name));
                    });
                }
               
                router.resolve();
            } else {
                showErrorDialog(manager.isError ? "managerError." + manager.error : "");
            }
        });
    }

    protected doBasicLogin(username: string | undefined, password: string | undefined): PromiseLike<BasicLoginResult> {
        const deferred = new Util.Deferred<BasicLoginResult>();

        let u = username;
        let p = password;

        // language=CSS
        const styles = html`
            #login-logo {
                width: 24px;
                height: 24px;
            }
            
            #login_wrapper > or-input {
                margin: 10px 0;
                width: 100%;
            }
        `;

        const dialog = showDialog({
            styles: html`<style>${styles}</style>`,
            title: html`<img id="login-logo" src="${this._config.logoMobile || this._config.logo}" /></or-icon><or-translate value="login"></or-translate>`,
            content: html`
                <div id="login_wrapper">
                    <or-input .label="${i18next.t("user")}" .type="${InputType.TEXT}" min="1" required .value="${username}" @or-input-changed="${(e: OrInputChangedEvent) => u = e.detail.value}"></or-input>            
                    <or-input .label="${i18next.t("password")}" .type="${InputType.PASSWORD}" min="1" required .value="${password}" @or-input-changed="${(e: OrInputChangedEvent) => p = e.detail.value}"></or-input>           
                </div>
            `,
            actions: [
                {
                    actionName: "submit",
                    default: true,
                    action: () => {
                        deferred.resolve({
                            cancel: false,
                            username: u!,
                            password: p!
                        });
                    },
                    content: html`<or-input .type=${InputType.BUTTON} .label="${i18next.t("submit")}" raised></or-input>`
                }
            ]
        }, document.body); // Attach to document as or-app isn't visible until initialised

        return deferred.promise;
    }

    protected updated(changedProps: PropertyValues) {
        super.updated(changedProps);

        if (!this._initialised) {
            return;
        }

        if (changedProps.has("_page")) {
            const appTitle = this._config.appTitle || "";
            let pageTitle = (i18next.isInitialized ? i18next.t(appTitle) : appTitle);

            if (this._mainElem) {
                if (this._mainElem.firstElementChild) {
                    this._mainElem.firstElementChild.remove();
                }
                if (this._page) {
                    const pageProvider = this.appConfig.pages.find((page) => page.name === this._page);
                    if (pageProvider) {
                        const pageElem = pageProvider.pageCreator();
                        this._mainElem.appendChild(pageElem);
                        pageTitle += (i18next.isInitialized ? " - " + i18next.t(pageElem.name) : " - " + pageElem.name);
                    }
                }
            }

            updateMetadata({
                title: pageTitle,
                description: pageTitle
            });
        }
    }

    protected render(): TemplateResult | void {

        if (!this._initialised) {
            return html`<or-mwc-dialog id="app-modal"></or-mwc-dialog>`;
        }
        let consoleStyles;
        if(manager.consoleAppConfig) {
            const consoleAppConfig = manager.consoleAppConfig;
            const primary = consoleAppConfig.primaryColor;
            const secondary = consoleAppConfig.secondaryColor;
            consoleStyles = html`<style>:host {--or-console-primary-color:${primary};--or-console-secondary-color:${secondary};}</style>`;
        }
        return html`
            ${unsafeHTML(this._config.styles ? this._config.styles.strings : ``)}
            ${consoleStyles}
            ${this._config.header ? html`
                <or-header logo="${this._config.logo}" .logoMobile="${this._config.logoMobile}" .config="${this._config.header}"></or-header>
            ` : ``}
            
            <!-- Main content -->
            <main role="main" class="main-content d-none"></main>
            
            <slot></slot>
        `;
    }

    public logout() {
        manager.logout();
    }

    public setLanguage(lang: string) {
        manager.language = lang;
    }

    public showLanguageModal() {
        showDialog(this._getLanguageModalConfig(DEFAULT_LANGUAGES));
    }

    public stateChanged(state: S) {
        this._page = state.app!.page;
    }

    protected _getConfig(realm: string | undefined): RealmAppConfig {
        realm = realm || "default";
        const defaultConfig = this.appConfig.realms ? this.appConfig.realms.default : {};
        const realmConfig = this.appConfig.realms ? this.appConfig.realms![realm] : undefined;
        return Util.mergeObjects(defaultConfig, realmConfig, false);
    }

    protected _getLanguageModalConfig(languages: Languages): DialogConfig {
        const title = "language";
        const actions = Object.entries(languages).map(([key, value]) => {
            return {
                content: i18next.t(value),
                actionName: key,
                action: () => {
                    manager.language = key;
                }
            };
        });

        return {
            title: title,
            actions: actions
        };
    }
}
