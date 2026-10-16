import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import './style.css';

async function api(path, method = 'GET') {
    const response = await fetch('/api' + path, {method});
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.detail || body.message || `Ошибка ${response.status}. Обновите список задач.`);
    }
    return response.status === 204 ? null : response.json();
}

function App() {
    const [tasks, setTasks] = useState([]), [user, setUser] = useState('demo');
    const [selected, setSelected] = useState(null), [error, setError] = useState('');
    const [busy, setBusy] = useState(false), [loaded, setLoaded] = useState(false);
    const [view, setView] = useState('active');
    const isHistory = view === 'completed';

    async function refresh() {
        const data = await api(isHistory ? '/tasks/completed' : '/tasks');
        setTasks(data.tasks);
        setUser(data.user);
        setLoaded(true);
    }

    useEffect(() => {
        let active = true;
        const poll = async () => {
            try {
                const data = await api(isHistory ? '/tasks/completed' : '/tasks');
                if (active) {
                    setTasks(data.tasks);
                    setUser(data.user);
                    setLoaded(true);
                }
            } catch (e) {
                if (active) setError(e.message);
            }
        };
        poll();
        const timer = setInterval(poll, 5000);
        return () => {
            active = false;
            clearInterval(timer);
        };
    }, [view]);

    function changeView(value) {
        setView(value);
        setSelected(null);
        setTasks([]);
        setLoaded(false);
        setError('');
    }

    async function open(id) {
        setBusy(true);
        setError('');
        try {
            setSelected(await api(isHistory ? `/tasks/completed/${id}` : `/tasks/${id}/open`, isHistory ? 'GET' : 'POST'));
            await refresh();
        } catch (e) {
            setError(e.message);
            await refresh().catch(() => {
            });
        } finally {
            setBusy(false);
        }
    }

    async function complete() {
        setBusy(true);
        setError('');
        try {
            await api(`/tasks/${selected.id}/complete`, 'POST');
            setSelected(null);
            await refresh();
        } catch (e) {
            setError(e.message);
        } finally {
            setBusy(false);
        }
    }

    return <main>
        <header>
            <div><span className="eyebrow">ОБОРУДОВАНИЕ / CAMUNDA 7</span><h1>Просмотр заявок</h1><p>Свободные задачи и
                задачи, назначенные вам</p></div>
            <span className="user">● {user}</span></header>
        {error && <div role="alert" className="error">{error}
            <button onClick={() => setError('')}>Закрыть</button>
        </div>}
        <nav className="tabs" aria-label="Списки заявок">
            <button disabled={busy} aria-pressed={!isHistory} onClick={() => changeView('active')}>Активные</button>
            <button disabled={busy} aria-pressed={isHistory} onClick={() => changeView('completed')}>Закрытые мной
            </button>
        </nav>
        <div className="layout">
            <section className="panel">
                <div className="section-head"><h2>{isHistory ? 'Закрытые мной' : 'Активные задачи'}
                    <small>{tasks.length}</small></h2>
                    <button disabled={busy}
                            onClick={() => refresh().then(() => setError('')).catch(e => setError(e.message))}>Обновить
                    </button>
                </div>
                {!loaded ? <p className="empty">Загрузка…</p> : tasks.length === 0 ?
                    <div className="empty"><h3>{isHistory ? 'Закрытых заявок пока нет' : 'Задач пока нет'}</h3>
                        <p>{isHistory ? 'Здесь появятся заявки, которые вы завершили кнопкой «Просмотрено / Завершить».' : 'Положите DOCX с таблицей uid, name во входную папку. Заявка появится автоматически.'}</p>
                    </div> : <div className="task-list">{tasks.map(task => <button disabled={busy}
                                                                                   className={'task ' + (selected?.id === task.id ? 'selected' : '')}
                                                                                   key={task.id}
                                                                                   onClick={() => open(task.id)}>
                        <span
                            className="badge">{isHistory ? 'Завершена' : task.assignee ? 'Назначена мне' : 'Свободна'}</span><strong>{task.fileName || task.name}</strong><span>{task.name}</span>
                        <time>{isHistory ? 'Завершена: ' : ''}{new Date(task.completedAt || task.created).toLocaleString('ru-RU')}</time>
                    </button>)}</div>}
            </section>
            <section className="panel detail">{!selected ?
                <div className="empty"><h2>Выберите {isHistory ? 'заявку' : 'задачу'}</h2>
                    <p>{isHistory ? 'Карточка завершённой заявки доступна для просмотра.' : 'При открытии задача автоматически назначается вам.'}</p>
                </div> : <>
                    <div className="section-head"><h2>{selected.fileName || selected.name}</h2>
                        <button disabled={busy} onClick={() => setSelected(null)}>Закрыть</button>
                    </div>
                    <dl>
                        <dt>ID заявки</dt>
                        <dd>{selected.requestId || '—'}</dd>
                        <dt>Исполнитель</dt>
                        <dd>{selected.assignee}</dd>
                        <dt>ID процесса</dt>
                        <dd>{selected.processInstanceId}</dd>
                    </dl>
                    {isHistory && <dl>
                        <dt>Завершена</dt>
                        <dd>{new Date(selected.completedAt).toLocaleString('ru-RU')}</dd>
                    </dl>}
                    <h3>Оборудование <small>{selected.equipment.length}</small></h3>
                    <div className="table-wrap">
                        <table>
                            <thead>
                            <tr>
                                <th>UID / серийный номер</th>
                                <th>Наименование</th>
                            </tr>
                            </thead>
                            <tbody>{selected.equipment.map((item, i) => <tr key={i}>
                                <td>{item.uid}</td>
                                <td>{item.name}</td>
                            </tr>)}</tbody>
                        </table>
                    </div>
                    <footer>{isHistory ? <p>Заявка завершена. Данные доступны только для просмотра.</p> : <><p>Закрытие
                        формы оставляет задачу назначенной вам.</p>
                        <button className="primary" disabled={busy}
                                onClick={complete}>{busy ? 'Сохранение…' : 'Просмотрено / Завершить'}</button>
                    </>}</footer>
                </>}</section>
        </div>
    </main>;
}

createRoot(document.getElementById('root')).render(<App/>);
