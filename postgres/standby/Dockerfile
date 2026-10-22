FROM postgres:16

COPY standby-entrypoint.sh /usr/local/bin/standby-entrypoint.sh
RUN chmod +x /usr/local/bin/standby-entrypoint.sh

ENTRYPOINT ["standby-entrypoint.sh"]
CMD ["postgres"]
